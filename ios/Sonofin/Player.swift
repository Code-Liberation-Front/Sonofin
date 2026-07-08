import AVFoundation
import Foundation
import MediaPlayer
import UIKit

/// Lets AVPlayer stream from servers with self-signed certificates by
/// accepting the server-trust challenge the media pipeline raises.
final class InsecureAssetLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let shared = InsecureAssetLoader()
    static let queue = DispatchQueue(label: "sonofin.assetloader")

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForResponseTo authenticationChallenge: URLAuthenticationChallenge
    ) -> Bool {
        let space = authenticationChallenge.protectionSpace
        if space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = space.serverTrust {
            authenticationChallenge.sender?.use(URLCredential(trust: trust), for: authenticationChallenge)
        } else {
            authenticationChallenge.sender?.continueWithoutCredential(for: authenticationChallenge)
        }
        return true
    }
}

/// Playback engine: manages the queue, lock-screen controls, progress
/// reporting, history recording, and random autoplay continuation —
/// mirroring the Android PlaybackService.
@MainActor
final class PlayerManager: ObservableObject {

    @Published private(set) var queue: [Song] = []
    @Published private(set) var currentIndex: Int = -1
    @Published private(set) var isPlaying = false
    @Published var positionSec: Double = 0
    @Published private(set) var durationSec: Double = 0

    var currentSong: Song? {
        queue.indices.contains(currentIndex) ? queue[currentIndex] : nil
    }

    private let player = AVPlayer()
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var extendingQueue = false
    private var lastReportedAt = Date.distantPast

    let client: JellyfinClient
    let history: HistoryStore
    let downloads: DownloadManager

    var autoplayRandom: Bool {
        get { UserDefaults.standard.object(forKey: "autoplayRandom") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "autoplayRandom") }
    }

    init(client: JellyfinClient, history: HistoryStore, downloads: DownloadManager) {
        self.client = client
        self.history = history
        self.downloads = downloads

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor in
                self?.tick(time)
            }
        }
        setupRemoteCommands()
    }

    private func tick(_ time: CMTime) {
        positionSec = max(0, time.seconds.isFinite ? time.seconds : 0)
        if let duration = player.currentItem?.duration.seconds, duration.isFinite {
            durationSec = duration
        }
        isPlaying = player.timeControlStatus == .playing
        updateNowPlayingPosition()
        // Report listening progress to Jellyfin every ~15s while playing.
        if isPlaying, Date().timeIntervalSince(lastReportedAt) > 15, let song = currentSong {
            lastReportedAt = Date()
            let position = positionSec
            Task { await client.reportProgress(songId: song.id, positionSec: position) }
        }
    }

    // MARK: Queue control

    func play(songs: [Song], startIndex: Int = 0) {
        guard !songs.isEmpty else { return }
        queue = songs
        currentIndex = min(max(0, startIndex), songs.count - 1)
        startCurrent()
    }

    func playSong(_ song: Song) {
        play(songs: [song])
    }

    /// Inserts a song right after the current one; starts playback if idle.
    func playNext(_ song: Song) {
        if queue.isEmpty {
            play(songs: [song])
        } else {
            queue.insert(song, at: min(currentIndex + 1, queue.count))
        }
    }

    func next() {
        guard currentIndex + 1 < queue.count else { return }
        currentIndex += 1
        startCurrent()
    }

    func previous() {
        // Apple-style: restart the song when a few seconds in.
        if positionSec > 3 || currentIndex <= 0 {
            seek(to: 0)
            return
        }
        currentIndex -= 1
        startCurrent()
    }

    func jump(to index: Int) {
        guard queue.indices.contains(index) else { return }
        currentIndex = index
        startCurrent()
    }

    func togglePlayPause() {
        if isPlaying {
            player.pause()
        } else {
            player.play()
        }
        isPlaying = player.timeControlStatus == .playing
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        positionSec = seconds
    }

    private func startCurrent() {
        guard let song = currentSong else { return }
        let url = downloads.localURL(songId: song.id) ?? client.streamURL(songId: song.id)
        guard let url else { return }

        if let old = endObserver {
            NotificationCenter.default.removeObserver(old)
        }
        let item: AVPlayerItem
        if url.isFileURL {
            item = AVPlayerItem(url: url)
        } else {
            let asset = AVURLAsset(url: url)
            asset.resourceLoader.setDelegate(InsecureAssetLoader.shared, queue: InsecureAssetLoader.queue)
            item = AVPlayerItem(asset: asset)
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.itemEnded()
            }
        }
        player.replaceCurrentItem(with: item)
        player.play()
        positionSec = 0
        durationSec = song.durationSec
        history.record(albumId: song.albumId, songId: song.id, title: song.title, artist: song.artist)
        Task { await client.markPlayed(songId: song.id) }
        updateNowPlayingInfo(song: song)
    }

    private func itemEnded() {
        if currentIndex + 1 < queue.count {
            currentIndex += 1
            startCurrent()
        } else if autoplayRandom {
            extendWithRandomSongs(playFirstAppended: true)
        } else {
            isPlaying = false
        }
    }

    /// Appends random library songs when the queue runs dry and autoplay is on.
    private func extendWithRandomSongs(playFirstAppended: Bool) {
        guard !extendingQueue else { return }
        extendingQueue = true
        Task {
            defer { extendingQueue = false }
            let existing = Set(queue.map(\.id))
            let random = ((try? await client.randomSongs(limit: 25)) ?? [])
                .filter { !existing.contains($0.id) && !$0.id.isEmpty }
                .prefix(10)
            guard !random.isEmpty else { return }
            let firstAppendedIndex = queue.count
            queue.append(contentsOf: random)
            if playFirstAppended {
                currentIndex = firstAppendedIndex
                startCurrent()
            }
        }
    }

    // MARK: Lock screen / control center

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.player.play() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.player.pause() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.next() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.previous() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            if let positionEvent = event as? MPChangePlaybackPositionCommandEvent {
                let seconds = positionEvent.positionTime
                Task { @MainActor in self?.seek(to: seconds) }
            }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }
    }

    private func updateNowPlayingInfo(song: Song) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPMediaItemPropertyArtist: song.artist,
            MPMediaItemPropertyAlbumTitle: song.albumName,
            MPMediaItemPropertyPlaybackDuration: song.durationSec,
        ]
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        // Fetch artwork asynchronously and attach it once loaded.
        if let artURL = client.imageURL(albumId: song.albumId) {
            let songId = song.id
            Task {
                guard let (data, _) = try? await Net.session.data(from: artURL),
                      let image = UIImage(data: data) else { return }
                guard self.currentSong?.id == songId else { return }
                let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                info[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }
    }

    private func updateNowPlayingPosition() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionSec
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        info[MPMediaItemPropertyPlaybackDuration] = durationSec
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
