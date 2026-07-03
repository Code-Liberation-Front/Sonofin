import AVKit
import SwiftUI

struct PlayerView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var playlists: PlaylistStore
    @EnvironmentObject private var historyStore: HistoryStore
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss

    @State private var lyricsOpen = false
    @State private var queueOpen = false
    @State private var dragPosition: Double?

    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.down").font(.title3)
                }
                Spacer()
            }
            .padding(.horizontal)

            if let song = player.currentSong {
                CoverArt(url: client.imageURL(albumId: song.albumId), size: 300, corner: 16)

                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(song.title).font(.title3).bold().lineLimit(2)
                        Menu {
                            if !song.albumId.isEmpty {
                                Button {
                                    dismiss()
                                    router.openAlbumFromPlayer(song.albumId)
                                } label: {
                                    Label("Go to Album", systemImage: "square.stack")
                                }
                            }
                            Button {
                                dismiss()
                                router.openArtistFromPlayer(song.artist)
                            } label: {
                                Label("Go to Artist", systemImage: "person")
                            }
                        } label: {
                            Text(song.artist.isEmpty ? "Unknown Artist" : song.artist)
                                .font(.subheadline)
                                .foregroundColor(.accentColor)
                                .lineLimit(1)
                        }
                        if !song.albumName.isEmpty && song.albumName != song.artist {
                            Text(song.albumName).font(.caption).foregroundColor(.secondary).lineLimit(1)
                        }
                    }
                    Spacer()
                    Button {
                        playlists.toggleFavorite(
                            PlaylistEntry(albumId: song.albumId, songId: song.id, title: song.title, artist: song.artist)
                        )
                    } label: {
                        Image(systemName: isFavorite(song) ? "heart.fill" : "heart")
                            .font(.title2)
                            .foregroundColor(isFavorite(song) ? .accentColor : .secondary)
                    }
                }
                .padding(.horizontal, 24)

                // Seek bar
                VStack(spacing: 4) {
                    Slider(
                        value: Binding(
                            get: { dragPosition ?? player.positionSec },
                            set: { dragPosition = $0 }
                        ),
                        in: 0...max(player.durationSec, 1)
                    ) { editing in
                        if !editing, let target = dragPosition {
                            player.seek(to: target)
                            dragPosition = nil
                        }
                    }
                    HStack {
                        Text(formatDuration(dragPosition ?? player.positionSec))
                        Spacer()
                        Text("-" + formatDuration(max(0, player.durationSec - (dragPosition ?? player.positionSec))))
                    }
                    .font(.caption)
                    .foregroundColor(.secondary)
                }
                .padding(.horizontal, 24)

                // Transport
                HStack(spacing: 48) {
                    Button { player.previous() } label: {
                        Image(systemName: "backward.fill").font(.system(size: 32))
                    }
                    Button { player.togglePlayPause() } label: {
                        Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 72))
                    }
                    Button { player.next() } label: {
                        Image(systemName: "forward.fill").font(.system(size: 32))
                    }
                }

                Spacer()

                // Bottom row: lyrics, AirPlay, queue/history.
                HStack {
                    Button { lyricsOpen = true } label: {
                        Image(systemName: "quote.bubble").font(.title3)
                    }
                    Spacer()
                    AirPlayButton()
                        .frame(width: 44, height: 44)
                    Spacer()
                    Button { queueOpen = true } label: {
                        Image(systemName: "list.bullet").font(.title3)
                    }
                }
                .padding(.horizontal, 32)
                .padding(.bottom, 12)
            } else {
                Spacer()
                Text("Nothing playing").foregroundColor(.secondary)
                Spacer()
            }
        }
        .padding(.top, 12)
        .background(Color(uiColor: .systemBackground))
        .sheet(isPresented: $lyricsOpen) {
            LyricsSheet()
        }
        .sheet(isPresented: $queueOpen) {
            QueueSheet(dismissPlayer: { dismiss() })
        }
    }

    private func isFavorite(_ song: Song) -> Bool {
        playlists.isFavorite(albumId: song.albumId, songId: song.id)
    }
}

// MARK: - AirPlay

struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = UIColor.secondaryLabel
        view.activeTintColor = UIColor(red: 0.66, green: 0.33, blue: 0.97, alpha: 1)
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

// MARK: - Lyrics

struct LyricsSheet: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager

    @State private var lines: [LyricLine]?

    var body: some View {
        NavigationStack {
            Group {
                if let lines {
                    if lines.isEmpty {
                        Text("No lyrics found for this song.")
                            .foregroundColor(.secondary)
                            .padding()
                    } else {
                        lyricsList(lines)
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Lyrics")
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            guard let song = player.currentSong else {
                lines = []
                return
            }
            lines = await client.lyrics(songId: song.id)
        }
    }

    @ViewBuilder
    private func lyricsList(_ lines: [LyricLine]) -> some View {
        let synced = lines.contains { $0.startMs != nil }
        let positionMs = Int64(player.positionSec * 1000)
        let currentIndex = synced
            ? (lines.lastIndex { ($0.startMs ?? Int64.max) <= positionMs } ?? -1)
            : -1
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                        Text(line.text.isEmpty ? "…" : line.text)
                            .font(.headline)
                            .foregroundColor(
                                index == currentIndex
                                    ? .accentColor
                                    : (synced && index < currentIndex ? .secondary : .primary)
                            )
                            .id(index)
                            .onTapGesture {
                                if let start = line.startMs {
                                    player.seek(to: Double(start) / 1000.0)
                                }
                            }
                    }
                }
                .padding(24)
            }
            .onChange(of: currentIndex) { newIndex in
                guard synced, newIndex >= 0 else { return }
                withAnimation {
                    proxy.scrollTo(newIndex, anchor: .center)
                }
            }
        }
    }
}

// MARK: - Queue / History

struct QueueSheet: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var historyStore: HistoryStore
    @Environment(\.dismiss) private var dismiss

    var dismissPlayer: () -> Void

    var body: some View {
        NavigationStack {
            List {
                let upNext = upNextEntries()
                if !upNext.isEmpty {
                    Section("Playing Next") {
                        ForEach(upNext, id: \.index) { entry in
                            queueRow(title: entry.song.title, artist: entry.song.artist, albumId: entry.song.albumId) {
                                player.jump(to: entry.index)
                                dismiss()
                            }
                        }
                    }
                }
                if !historyStore.history.isEmpty {
                    Section("History") {
                        ForEach(historyStore.history) { played in
                            queueRow(title: played.title, artist: played.artist, albumId: played.albumId) {
                                let client = client
                                let player = player
                                Task {
                                    let songs = sortedByAlbumOrder((try? await client.albumSongs(albumId: played.albumId)) ?? [])
                                    if let index = songs.firstIndex(where: { $0.id == played.songId }) {
                                        player.play(songs: songs, startIndex: index)
                                    }
                                }
                                dismiss()
                            }
                        }
                    }
                }
                if upNext.isEmpty && historyStore.history.isEmpty {
                    Text("Nothing here yet — play some music.")
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Up Next")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private struct UpNextEntry {
        let index: Int
        let song: Song
    }

    private func upNextEntries() -> [UpNextEntry] {
        guard player.currentIndex >= 0 else { return [] }
        let start = player.currentIndex + 1
        guard start < player.queue.count else { return [] }
        return (start..<player.queue.count).map { UpNextEntry(index: $0, song: player.queue[$0]) }
    }

    @ViewBuilder
    private func queueRow(title: String, artist: String, albumId: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                CoverArt(url: client.imageURL(albumId: albumId), size: 44, corner: 6)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline).bold().foregroundColor(.primary).lineLimit(1)
                    if !artist.isEmpty {
                        Text(artist).font(.caption).foregroundColor(.secondary).lineLimit(1)
                    }
                }
            }
        }
    }
}
