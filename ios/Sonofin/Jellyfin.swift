import Foundation

// MARK: - Domain models

struct Album: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var artist: String
    var trackCount: Int
    var addedAt: Date?
}

struct Song: Codable, Identifiable, Hashable {
    let id: String
    var albumId: String
    var title: String
    var artist: String
    var albumName: String
    var durationSec: Double
    var discNumber: Int
    var trackNumber: Int
    var genres: [String]
}

struct LyricLine: Codable, Hashable {
    var text: String
    var startMs: Int64?
}

struct Mix: Codable, Identifiable, Hashable {
    let id: String
    var title: String
    var subtitle: String
    var songs: [Song]
}

struct ArtistEntry: Identifiable, Hashable {
    var id: String { name }
    var name: String
    var albumCount: Int
    var coverAlbumId: String
}

// MARK: - Jellyfin wire types

private struct JFAuthResult: Decodable {
    struct JFUser: Decodable {
        let Id: String
        let Name: String?
    }
    let User: JFUser
    let AccessToken: String
}

private struct JFViews: Decodable {
    let Items: [JFView]
}

private struct JFView: Decodable {
    let Id: String
    let Name: String?
    let CollectionType: String?
}

private struct JFItems: Decodable {
    let Items: [JFItem]
    let TotalRecordCount: Int?
}

private struct JFItem: Decodable {
    let Id: String
    let Name: String?
    let AlbumId: String?
    let Album: String?
    let AlbumArtist: String?
    let Artists: [String]?
    let IndexNumber: Int?
    let ParentIndexNumber: Int?
    let RunTimeTicks: Int64?
    let ChildCount: Int?
    let DateCreated: String?
    let Genres: [String]?
}

private struct JFLyrics: Decodable {
    struct Line: Decodable {
        let Text: String?
        let Start: Int64?
    }
    let Lyrics: [Line]?
}

// MARK: - Client

/// Jellyfin API client with a JSON disk cache. Screens read the cache first,
/// then revalidate against the server (matching the Android app).
final class JellyfinClient: ObservableObject {

    @Published var isLoggedIn: Bool

    private(set) var server: String
    private(set) var token: String
    private(set) var userId: String
    private(set) var libraryId: String
    let deviceId: String

    private let cacheDir: URL

    private static let iso = ISO8601DateFormatter()
    private static let isoFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    init() {
        let defaults = UserDefaults.standard
        server = defaults.string(forKey: "server") ?? ""
        token = defaults.string(forKey: "token") ?? ""
        userId = defaults.string(forKey: "userId") ?? ""
        libraryId = defaults.string(forKey: "libraryId") ?? ""
        if let existing = defaults.string(forKey: "deviceId") {
            deviceId = existing
        } else {
            let generated = UUID().uuidString.replacingOccurrences(of: "-", with: "")
            defaults.set(generated, forKey: "deviceId")
            deviceId = generated
        }
        isLoggedIn = !server.isEmpty && !token.isEmpty
        cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("apicache", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
    }

    // MARK: Auth

    private var authHeader: String {
        var value = "MediaBrowser Client=\"Sonofin\", Device=\"iPhone\", DeviceId=\"\(deviceId)\", Version=\"1\""
        if !token.isEmpty { value += ", Token=\"\(token)\"" }
        return value
    }

    static func normalizeServer(_ input: String) -> String {
        var url = input.trimmingCharacters(in: .whitespacesAndNewlines)
        while url.hasSuffix("/") { url = String(url.dropLast()) }
        if !url.isEmpty && !url.hasPrefix("http://") && !url.hasPrefix("https://") {
            url = "https://" + url
        }
        return url
    }

    func login(server serverInput: String, username: String, password: String) async throws {
        let base = Self.normalizeServer(serverInput)
        guard let url = URL(string: "\(base)/Users/AuthenticateByName") else {
            throw SonofinError.message("Invalid server URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(authHeader, forHTTPHeaderField: "Authorization")
        request.setValue(authHeader, forHTTPHeaderField: "X-Emby-Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["Username": username, "Pw": password])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw SonofinError.message("Login failed — check the server URL, username, and password")
        }
        let result = try JSONDecoder().decode(JFAuthResult.self, from: data)
        server = base
        token = result.AccessToken
        userId = result.User.Id
        let defaults = UserDefaults.standard
        defaults.set(server, forKey: "server")
        defaults.set(token, forKey: "token")
        defaults.set(userId, forKey: "userId")
        defaults.set(result.User.Name ?? username, forKey: "username")
        await MainActor.run { isLoggedIn = true }
    }

    func logout() {
        let defaults = UserDefaults.standard
        for key in ["server", "token", "userId", "username", "libraryId"] {
            defaults.removeObject(forKey: key)
        }
        server = ""
        token = ""
        userId = ""
        libraryId = ""
        try? FileManager.default.removeItem(at: cacheDir)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        Task { @MainActor in isLoggedIn = false }
    }

    var username: String { UserDefaults.standard.string(forKey: "username") ?? "" }

    // MARK: HTTP

    private func get(_ path: String, query: [String: String] = [:]) async throws -> Data {
        guard var components = URLComponents(string: "\(server)/\(path)") else {
            throw SonofinError.message("Bad URL")
        }
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = components.url else { throw SonofinError.message("Bad URL") }
        var request = URLRequest(url: url)
        request.setValue(authHeader, forHTTPHeaderField: "Authorization")
        request.setValue(authHeader, forHTTPHeaderField: "X-Emby-Authorization")
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw SonofinError.message("Server error (\((response as? HTTPURLResponse)?.statusCode ?? 0))")
        }
        return data
    }

    private func post(_ path: String, body: [String: Any]) async {
        guard let url = URL(string: "\(server)/\(path)") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(authHeader, forHTTPHeaderField: "Authorization")
        request.setValue(authHeader, forHTTPHeaderField: "X-Emby-Authorization")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        _ = try? await URLSession.shared.data(for: request)
    }

    // MARK: Mapping

    private static func parseDate(_ value: String?) -> Date? {
        guard let value else { return nil }
        return isoFractional.date(from: value) ?? iso.date(from: value)
    }

    private func toAlbum(_ item: JFItem) -> Album {
        Album(
            id: item.Id,
            name: item.Name ?? "Album",
            artist: item.AlbumArtist ?? "",
            trackCount: item.ChildCount ?? 0,
            addedAt: Self.parseDate(item.DateCreated)
        )
    }

    private func toSong(_ item: JFItem) -> Song {
        let artists = (item.Artists ?? []).joined(separator: ", ")
        return Song(
            id: item.Id,
            albumId: item.AlbumId ?? "",
            title: item.Name ?? "Song",
            artist: artists.isEmpty ? (item.AlbumArtist ?? "") : artists,
            albumName: item.Album ?? "",
            durationSec: Double(item.RunTimeTicks ?? 0) / 10_000_000.0,
            discNumber: item.ParentIndexNumber ?? 0,
            trackNumber: item.IndexNumber ?? 0,
            genres: item.Genres ?? []
        )
    }

    // MARK: Library selection

    func resolveLibraryId() async throws -> String {
        if !libraryId.isEmpty { return libraryId }
        let data = try await get("Users/\(userId)/Views")
        let views = try JSONDecoder().decode(JFViews.self, from: data).Items
        let pick = views.first { $0.CollectionType == "music" } ?? views.first
        guard let pick else { throw SonofinError.message("No libraries found on this server") }
        libraryId = pick.Id
        UserDefaults.standard.set(libraryId, forKey: "libraryId")
        return libraryId
    }

    func libraries() async throws -> [(id: String, name: String)] {
        let data = try await get("Users/\(userId)/Views")
        let views = try JSONDecoder().decode(JFViews.self, from: data).Items
        return views.map { ($0.Id, $0.Name ?? "Library") }
    }

    func selectLibrary(id: String) {
        libraryId = id
        UserDefaults.standard.set(id, forKey: "libraryId")
        try? FileManager.default.removeItem(at: cacheDir)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
    }

    // MARK: Queries

    private func items(_ query: [String: String]) async throws -> [JFItem] {
        var q = query
        q["UserId"] = userId
        let data = try await get("Items", query: q)
        return try JSONDecoder().decode(JFItems.self, from: data).Items
    }

    /// All albums, name order.
    func albums() async throws -> [Album] {
        let library = try await resolveLibraryId()
        let result = try await items([
            "ParentId": library,
            "IncludeItemTypes": "MusicAlbum",
            "Recursive": "true",
            "SortBy": "SortName",
            "SortOrder": "Ascending",
            "Fields": "DateCreated,ChildCount",
        ]).map(toAlbum)
        cacheWrite("albums.json", result)
        return result
    }

    /// Newest albums via a dedicated small query.
    func recentlyAdded(limit: Int = 26) async throws -> [Album] {
        let library = try await resolveLibraryId()
        let result = try await items([
            "ParentId": library,
            "IncludeItemTypes": "MusicAlbum",
            "Recursive": "true",
            "SortBy": "DateCreated",
            "SortOrder": "Descending",
            "Limit": String(limit),
            "Fields": "DateCreated,ChildCount",
        ]).map(toAlbum)
        cacheWrite("recent_albums.json", result)
        return result
    }

    /// Most-played albums; falls back to a daily rotation for fresh libraries.
    func topPicks(limit: Int = 10) async throws -> [Album] {
        let library = try await resolveLibraryId()
        var result = (try? await items([
            "ParentId": library,
            "IncludeItemTypes": "MusicAlbum",
            "Recursive": "true",
            "SortBy": "PlayCount",
            "SortOrder": "Descending",
            "Limit": String(limit),
            "Fields": "DateCreated,ChildCount",
        ]).map(toAlbum)) ?? []
        if result.isEmpty {
            var generator = SeededGenerator(seed: UInt64(Self.daySeed()))
            result = Array(((try? await albums()) ?? []).shuffled(using: &generator).prefix(limit))
        }
        cacheWrite("toppicks.json", result)
        return result
    }

    /// Recently played songs (server DatePlayed order).
    func recentlyPlayed(limit: Int = 12) async throws -> [Song] {
        let result = try await items([
            "IncludeItemTypes": "Audio",
            "Recursive": "true",
            "SortBy": "DatePlayed",
            "SortOrder": "Descending",
            "Filters": "IsPlayed",
            "Limit": String(limit),
            "Fields": "Genres",
        ]).map(toSong)
        cacheWrite("recent_songs.json", result)
        return result
    }

    /// An album's tracks in disc/track order. Recursive so folder-backed
    /// albums (tracks nested under disc folders) also resolve.
    func albumSongs(albumId: String) async throws -> [Song] {
        let result = try await items([
            "ParentId": albumId,
            "IncludeItemTypes": "Audio",
            "Recursive": "true",
            "SortBy": "ParentIndexNumber,IndexNumber,SortName",
            "SortOrder": "Ascending",
            "Fields": "Genres",
        ]).map(toSong)
        cacheWrite("album_\(albumId).json", result)
        return result
    }

    /// One page of the library's songs, A-Z.
    func songsPage(startIndex: Int, limit: Int = 50) async throws -> (songs: [Song], total: Int) {
        let library = try await resolveLibraryId()
        var q = [
            "UserId": userId,
            "ParentId": library,
            "IncludeItemTypes": "Audio",
            "Recursive": "true",
            "SortBy": "SortName",
            "SortOrder": "Ascending",
            "StartIndex": String(startIndex),
            "Limit": String(limit),
            "Fields": "Genres",
        ]
        q["UserId"] = userId
        let data = try await get("Items", query: q)
        let decoded = try JSONDecoder().decode(JFItems.self, from: data)
        let songs = decoded.Items.map(toSong)
        if startIndex == 0 {
            cacheWrite("songs_page0.json", songs)
            cacheWrite("songs_total.json", decoded.TotalRecordCount ?? songs.count)
        }
        return (songs, decoded.TotalRecordCount ?? songs.count)
    }

    /// A bounded random sample, for mixes and autoplay continuation.
    func randomSongs(limit: Int) async throws -> [Song] {
        let library = try await resolveLibraryId()
        return try await items([
            "ParentId": library,
            "IncludeItemTypes": "Audio",
            "Recursive": "true",
            "SortBy": "Random",
            "Limit": String(limit),
            "Fields": "Genres",
        ]).map(toSong)
    }

    func search(_ term: String) async throws -> (albums: [Album], songs: [Song]) {
        let albumItems = (try? await items([
            "IncludeItemTypes": "MusicAlbum",
            "Recursive": "true",
            "SearchTerm": term,
            "Limit": "40",
            "Fields": "DateCreated,ChildCount",
        ]).map(toAlbum)) ?? []
        let songItems = (try? await items([
            "IncludeItemTypes": "Audio",
            "Recursive": "true",
            "SearchTerm": term,
            "Limit": "40",
            "Fields": "Genres",
        ]).map(toSong)) ?? []
        return (albumItems, songItems)
    }

    func lyrics(songId: String) async -> [LyricLine] {
        guard let data = try? await get("Audio/\(songId)/Lyrics") else { return [] }
        guard let decoded = try? JSONDecoder().decode(JFLyrics.self, from: data) else { return [] }
        return (decoded.Lyrics ?? []).map {
            LyricLine(text: $0.Text ?? "", startMs: $0.Start.map { $0 / 10_000 })
        }
    }

    // MARK: Made for You mixes (generated locally, like Android)

    func madeForYou(force: Bool = false) async -> [Mix] {
        if !force, let cached: [Mix] = cacheRead("mixes.json"), !cached.isEmpty {
            return cached
        }
        guard let sample = try? await randomSongs(limit: 300), !sample.isEmpty else {
            return cacheRead("mixes.json") ?? []
        }
        var mixes: [Mix] = []
        var generator = SeededGenerator(seed: UInt64(Self.daySeed()))
        var byGenre: [String: [Song]] = [:]
        for song in sample {
            for genre in song.genres where !genre.trimmingCharacters(in: .whitespaces).isEmpty {
                byGenre[genre, default: []].append(song)
            }
        }
        let genres = byGenre.filter { $0.value.count >= 4 }.sorted { $0.value.count > $1.value.count }
        for (genre, songs) in genres.prefix(4) {
            mixes.append(Mix(
                id: "genre:\(genre)",
                title: "\(genre) Mix",
                subtitle: "\(songs.count) songs in your library",
                songs: Array(songs.shuffled(using: &generator).prefix(25))
            ))
        }
        if mixes.count < 4 {
            let byArtist = Dictionary(grouping: sample.filter { !$0.artist.isEmpty }, by: { $0.artist })
                .filter { $0.value.count >= 4 }
                .sorted { $0.value.count > $1.value.count }
            for (artist, songs) in byArtist.prefix(4 - mixes.count) {
                mixes.append(Mix(
                    id: "artist:\(artist)",
                    title: "\(artist) Essentials",
                    subtitle: "The best of \(artist)",
                    songs: Array(songs.shuffled(using: &generator).prefix(25))
                ))
            }
        }
        mixes.append(Mix(
            id: "discovery",
            title: "Discovery Mix",
            subtitle: "Fresh picks from your library",
            songs: Array(sample.shuffled(using: &generator).prefix(25))
        ))
        cacheWrite("mixes.json", mixes)
        return mixes
    }

    func mix(id: String) async -> Mix? {
        await madeForYou().first { $0.id == id }
    }

    // MARK: Playback reporting

    func reportProgress(songId: String, positionSec: Double) async {
        await post("Sessions/Playing/Progress", body: [
            "ItemId": songId,
            "PositionTicks": Int64(positionSec * 10_000_000),
            "IsPaused": true,
            "PlayMethod": "DirectStream",
            "EventName": "timeupdate",
        ])
    }

    func markPlayed(songId: String) async {
        await post("Users/\(userId)/PlayedItems/\(songId)", body: [:])
    }

    // MARK: URLs

    func streamURL(songId: String) -> URL? {
        URL(string: "\(server)/Audio/\(songId)/stream?static=true&api_key=\(token)")
    }

    func imageURL(albumId: String) -> URL? {
        guard !albumId.isEmpty else { return nil }
        return URL(string: "\(server)/Items/\(albumId)/Images/Primary?maxHeight=600&api_key=\(token)")
    }

    // MARK: Disk cache

    func cacheWrite<T: Encodable>(_ name: String, _ value: T) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: cacheDir.appendingPathComponent(name))
    }

    func cacheRead<T: Decodable>(_ name: String) -> T? {
        guard let data = try? Data(contentsOf: cacheDir.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    static func daySeed() -> Int {
        Int(Date().timeIntervalSince1970 / 86_400)
    }
}

enum SonofinError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case .message(let text): return text
        }
    }
}

/// Deterministic RNG so daily shelves are stable while browsing.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed &+ 0x9E3779B97F4A7C15
    }

    mutating func next() -> UInt64 {
        state = state &+ 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}

/// Groups albums by artist, like the Android artistsFromAlbums.
func artistsFromAlbums(_ albums: [Album]) -> [ArtistEntry] {
    let grouped = Dictionary(grouping: albums) { album -> String in
        let name = album.artist.trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? "Unknown Artist" : name
    }
    return grouped
        .map { ArtistEntry(name: $0.key, albumCount: $0.value.count, coverAlbumId: $0.value[0].id) }
        .sorted { $0.name.lowercased() < $1.name.lowercased() }
}

/// Album order: disc, then track, then title.
func sortedByAlbumOrder(_ songs: [Song]) -> [Song] {
    songs.sorted {
        if $0.discNumber != $1.discNumber { return $0.discNumber < $1.discNumber }
        if $0.trackNumber != $1.trackNumber { return $0.trackNumber < $1.trackNumber }
        return $0.title < $1.title
    }
}
