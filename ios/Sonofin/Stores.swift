import Foundation

// MARK: - Persistence helper

private func documentsURL(_ name: String) -> URL {
    FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent(name)
}

private func loadJSON<T: Decodable>(_ name: String, fallback: T) -> T {
    guard let data = try? Data(contentsOf: documentsURL(name)),
          let decoded = try? JSONDecoder().decode(T.self, from: data) else { return fallback }
    return decoded
}

private func saveJSON<T: Encodable>(_ name: String, _ value: T) {
    guard let data = try? JSONEncoder().encode(value) else { return }
    try? data.write(to: documentsURL(name))
}

// MARK: - Pins

struct PinnedItem: Codable, Identifiable, Hashable {
    var kind: String // "song" | "album"
    var id: String // album id
    var songId: String
    var title: String
    var subtitle: String

    var key: String { "\(kind):\(id):\(songId)" }
}

/// Items pinned to the top of the Library tab.
@MainActor
final class PinStore: ObservableObject {
    @Published private(set) var pins: [PinnedItem]

    init() {
        pins = loadJSON("pins.json", fallback: [])
    }

    func isPinned(kind: String, id: String, songId: String = "") -> Bool {
        pins.contains { $0.kind == kind && $0.id == id && $0.songId == songId }
    }

    func toggle(_ item: PinnedItem) {
        if pins.contains(where: { $0.key == item.key }) {
            pins.removeAll { $0.key == item.key }
        } else {
            pins.append(item)
        }
        saveJSON("pins.json", pins)
    }

    func remove(_ item: PinnedItem) {
        pins.removeAll { $0.key == item.key }
        saveJSON("pins.json", pins)
    }
}

// MARK: - Playlists (including Favorites)

struct PlaylistEntry: Codable, Hashable {
    var albumId: String
    var songId: String
    var title: String
    var artist: String
}

struct UserPlaylist: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var entries: [PlaylistEntry]
}

/// Named local playlists; "Favorites" is created on first heart.
@MainActor
final class PlaylistStore: ObservableObject {
    static let favoritesName = "Favorites"

    @Published private(set) var playlists: [UserPlaylist]

    init() {
        playlists = loadJSON("playlists.json", fallback: [])
    }

    private func persist() {
        saveJSON("playlists.json", playlists)
    }

    @discardableResult
    func create(name: String) -> String {
        let id = UUID().uuidString
        playlists.append(UserPlaylist(id: id, name: name.isEmpty ? "Playlist" : name, entries: []))
        persist()
        return id
    }

    func delete(id: String) {
        playlists.removeAll { $0.id == id }
        persist()
    }

    func add(_ entry: PlaylistEntry, to playlistId: String) {
        guard let index = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
        guard !playlists[index].entries.contains(where: { $0.albumId == entry.albumId && $0.songId == entry.songId }) else { return }
        playlists[index].entries.append(entry)
        persist()
    }

    func remove(albumId: String, songId: String, from playlistId: String) {
        guard let index = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
        playlists[index].entries.removeAll { $0.albumId == albumId && $0.songId == songId }
        persist()
    }

    func setEntries(_ entries: [PlaylistEntry], for playlistId: String) {
        guard let index = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
        playlists[index].entries = entries
        persist()
    }

    func favoritesId() -> String {
        if let existing = playlists.first(where: { $0.name == Self.favoritesName }) {
            return existing.id
        }
        return create(name: Self.favoritesName)
    }

    func isFavorite(albumId: String, songId: String) -> Bool {
        playlists.first { $0.name == Self.favoritesName }?
            .entries.contains { $0.albumId == albumId && $0.songId == songId } ?? false
    }

    func toggleFavorite(_ entry: PlaylistEntry) {
        let id = favoritesId()
        if isFavorite(albumId: entry.albumId, songId: entry.songId) {
            remove(albumId: entry.albumId, songId: entry.songId, from: id)
        } else {
            add(entry, to: id)
        }
    }
}

// MARK: - Play history (all-time, last 50)

struct PlayedSong: Codable, Identifiable, Hashable {
    var id: String { "\(albumId):\(songId)" }
    var albumId: String
    var songId: String
    var title: String
    var artist: String
    var playedAt: Date
}

@MainActor
final class HistoryStore: ObservableObject {
    @Published private(set) var history: [PlayedSong]

    init() {
        history = loadJSON("history.json", fallback: [])
    }

    func record(albumId: String, songId: String, title: String, artist: String) {
        guard !songId.isEmpty else { return }
        history.removeAll { $0.albumId == albumId && $0.songId == songId }
        history.insert(
            PlayedSong(albumId: albumId, songId: songId, title: title, artist: artist, playedAt: Date()),
            at: 0
        )
        if history.count > 50 { history = Array(history.prefix(50)) }
        saveJSON("history.json", history)
    }
}

// MARK: - Downloads

struct DownloadedSong: Codable, Identifiable, Hashable {
    var id: String { "\(albumId):\(songId)" }
    var albumId: String
    var songId: String
    var title: String
    var artist: String
}

/// Downloads songs for offline playback into the app's Documents directory.
@MainActor
final class DownloadManager: ObservableObject {
    @Published private(set) var completed: [DownloadedSong]
    @Published private(set) var active: Set<String> = []

    private let dir: URL

    init() {
        dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        completed = loadJSON("downloads.json", fallback: [])
    }

    func isDownloaded(songId: String) -> Bool {
        completed.contains { $0.songId == songId }
    }

    func localURL(songId: String) -> URL? {
        let url = dir.appendingPathComponent("\(songId).audio")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func download(song: Song, client: JellyfinClient) {
        guard !isDownloaded(songId: song.id), !active.contains(song.id) else { return }
        guard let remote = client.streamURL(songId: song.id) else { return }
        active.insert(song.id)
        let destination = dir.appendingPathComponent("\(song.id).audio")
        Task {
            defer { active.remove(song.id) }
            do {
                let (temp, response) = try await URLSession.shared.download(from: remote)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return }
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: temp, to: destination)
                completed.append(DownloadedSong(albumId: song.albumId, songId: song.id, title: song.title, artist: song.artist))
                saveJSON("downloads.json", completed)
            } catch {
                // Leave the song un-downloaded; the user can retry.
            }
        }
    }

    func delete(songId: String) {
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(songId).audio"))
        completed.removeAll { $0.songId == songId }
        saveJSON("downloads.json", completed)
    }

    func toggle(song: Song, client: JellyfinClient) {
        if isDownloaded(songId: song.id) {
            delete(songId: song.id)
        } else {
            download(song: song, client: client)
        }
    }
}
