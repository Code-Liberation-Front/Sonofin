import SwiftUI

// MARK: - Navigation

enum Route: Hashable {
    case album(String)
    case artist(String)
    case albums
    case artists
    case songs
    case playlists
    case playlist(String)
    case mix(String)
    case settings
}

/// Cross-tab navigation (used by the player's "go to album/artist" menu).
@MainActor
final class Router: ObservableObject {
    @Published var tab = 0
    @Published var libraryPath = NavigationPath()

    func openAlbumFromPlayer(_ albumId: String) {
        tab = 1
        libraryPath.append(Route.album(albumId))
    }

    func openArtistFromPlayer(_ name: String) {
        tab = 1
        libraryPath.append(Route.artist(name))
    }
}

/// Screens that already revalidated against the server this app session.
@MainActor
enum SessionRefresh {
    static var done = Set<String>()

    static func claim(_ key: String) -> Bool {
        done.insert(key).inserted
    }
}

struct RouteDestination: View {
    let route: Route

    var body: some View {
        switch route {
        case .album(let id): AlbumView(albumId: id)
        case .artist(let name): ArtistDetailView(artistName: name)
        case .albums: AlbumsView()
        case .artists: ArtistsView()
        case .songs: SongsView()
        case .playlists: PlaylistsView()
        case .playlist(let id): PlaylistDetailView(playlistId: id)
        case .mix(let id): MixView(mixId: id)
        case .settings: SettingsView()
        }
    }
}

// MARK: - Shared rows and cards

struct SongRow: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var pins: PinStore
    @EnvironmentObject private var playlists: PlaylistStore
    @EnvironmentObject private var downloads: DownloadManager

    let song: Song
    var onTap: () -> Void
    var onOpenAlbum: ((String) -> Void)?
    var onRemoveFromPlaylist: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            CoverArt(url: client.imageURL(albumId: song.albumId), size: 48, corner: 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title)
                    .font(.subheadline).bold()
                    .foregroundColor(player.currentSong?.id == song.id ? .accentColor : .primary)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    if downloads.isDownloaded(songId: song.id) {
                        Image(systemName: "arrow.down.circle.fill")
                            .font(.caption2)
                            .foregroundColor(.accentColor)
                    }
                    Text(song.artist.isEmpty ? formatDuration(song.durationSec) : song.artist)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            Image(systemName: player.currentSong?.id == song.id && player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                .font(.title2)
                .foregroundColor(.accentColor)
        }
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
        .contextMenu { menuItems }
    }

    @ViewBuilder
    private var menuItems: some View {
        Button {
            player.playNext(song)
        } label: {
            Label("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward")
        }
        Button {
            pins.toggle(PinnedItem(kind: "song", id: song.albumId, songId: song.id, title: song.title, subtitle: song.artist))
        } label: {
            Label(pins.isPinned(kind: "song", id: song.albumId, songId: song.id) ? "Unpin" : "Pin", systemImage: "pin")
        }
        Menu {
            Button("New playlist…") {
                let id = playlists.create(name: "Playlist")
                playlists.add(entry, to: id)
            }
            ForEach(playlists.playlists) { playlist in
                Button(playlist.name) {
                    playlists.add(entry, to: playlist.id)
                }
            }
        } label: {
            Label("Add to Playlist", systemImage: "text.badge.plus")
        }
        Button {
            downloads.toggle(song: song, client: client)
        } label: {
            if downloads.isDownloaded(songId: song.id) {
                Label("Remove Download", systemImage: "trash")
            } else {
                Label("Download", systemImage: "arrow.down.circle")
            }
        }
        if let onOpenAlbum, !song.albumId.isEmpty {
            Button {
                onOpenAlbum(song.albumId)
            } label: {
                Label("Go to Album", systemImage: "square.stack")
            }
        }
        if let onRemoveFromPlaylist {
            Button(role: .destructive) {
                onRemoveFromPlaylist()
            } label: {
                Label("Remove from Playlist", systemImage: "minus.circle")
            }
        }
    }

    private var entry: PlaylistEntry {
        PlaylistEntry(albumId: song.albumId, songId: song.id, title: song.title, artist: song.artist)
    }
}

struct AlbumCard: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var pins: PinStore
    @EnvironmentObject private var downloads: DownloadManager

    let album: Album

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            CoverArtFlexible(url: client.imageURL(albumId: album.id))
            Text(album.name).font(.subheadline).bold().lineLimit(1)
            if !album.artist.isEmpty {
                Text(album.artist).font(.caption).foregroundColor(.secondary).lineLimit(1)
            }
        }
        .contextMenu {
            Button {
                playAlbum(shuffled: false)
            } label: {
                Label("Play", systemImage: "play")
            }
            Button {
                playAlbum(shuffled: true)
            } label: {
                Label("Shuffle", systemImage: "shuffle")
            }
            Button {
                pins.toggle(PinnedItem(kind: "album", id: album.id, songId: "", title: album.name, subtitle: album.artist))
            } label: {
                Label(pins.isPinned(kind: "album", id: album.id) ? "Unpin" : "Pin", systemImage: "pin")
            }
            Button {
                let client = client
                let downloads = downloads
                Task {
                    let songs = (try? await client.albumSongs(albumId: album.id)) ?? []
                    for song in songs {
                        downloads.download(song: song, client: client)
                    }
                }
            } label: {
                Label("Download Album", systemImage: "arrow.down.circle")
            }
        }
    }

    private func playAlbum(shuffled: Bool) {
        let client = client
        let player = player
        Task {
            var songs = sortedByAlbumOrder((try? await client.albumSongs(albumId: album.id)) ?? [])
            if shuffled { songs.shuffle() }
            player.play(songs: songs)
        }
    }
}

struct SectionHeader: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.headline)
            .foregroundColor(.accentColor)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal)
            .padding(.vertical, 6)
    }
}

struct PlayShuffleRow: View {
    var onPlay: () -> Void
    var onShuffle: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onPlay) {
                Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            Button(action: onShuffle) {
                Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(.horizontal)
    }
}

func formatDuration(_ seconds: Double) -> String {
    guard seconds > 0 else { return "" }
    let total = Int(seconds)
    let minutes = total / 60
    let secs = total % 60
    return String(format: "%d:%02d", minutes, secs)
}

// MARK: - Home

struct HomeView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var router: Router

    @State private var topPicks: [Album] = []
    @State private var recentSongs: [Song] = []
    @State private var mixes: [Mix] = []
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    SectionHeader(text: "Top Picks for You")
                    if topPicks.isEmpty {
                        hint("Play some music and your favorites will show up here.")
                    } else {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(topPicks) { album in
                                    Button {
                                        path.append(Route.album(album.id))
                                    } label: {
                                        AlbumCard(album: album).frame(width: 160)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                    SectionHeader(text: "Recently Played")
                    if recentSongs.isEmpty {
                        hint("Nothing played yet — pick an album and start listening.")
                    } else {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(recentSongs) { song in
                                    Button {
                                        playFromAlbum(song)
                                    } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            CoverArt(url: client.imageURL(albumId: song.albumId), size: 140, corner: 10)
                                            Text(song.title).font(.subheadline).bold().lineLimit(1)
                                            Text(song.artist).font(.caption).foregroundColor(.secondary).lineLimit(1)
                                        }
                                        .frame(width: 140, alignment: .leading)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                    if !mixes.isEmpty {
                        SectionHeader(text: "Made for You")
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(mixes) { mix in
                                    Button {
                                        path.append(Route.mix(mix.id))
                                    } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            ZStack(alignment: .bottomLeading) {
                                                CoverArt(
                                                    url: client.imageURL(albumId: mix.songs.first?.albumId ?? ""),
                                                    size: 150,
                                                    corner: 12
                                                )
                                                LinearGradient(
                                                    colors: [.clear, .black.opacity(0.75)],
                                                    startPoint: .top,
                                                    endPoint: .bottom
                                                )
                                                .frame(width: 150, height: 150)
                                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                                Text(mix.title)
                                                    .font(.subheadline).bold()
                                                    .foregroundColor(.white)
                                                    .padding(8)
                                            }
                                            Text(mix.subtitle).font(.caption).foregroundColor(.secondary).lineLimit(1)
                                        }
                                        .frame(width: 150, alignment: .leading)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                }
                .padding(.vertical)
            }
            .navigationTitle("Home")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        path.append(Route.settings)
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .navigationDestination(for: Route.self) { RouteDestination(route: $0) }
            .refreshable { await load(force: true) }
            .task { await initialLoad() }
        }
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundColor(.secondary)
            .padding(.horizontal)
    }

    private func playFromAlbum(_ song: Song) {
        let client = client
        let player = player
        Task {
            let songs = sortedByAlbumOrder((try? await client.albumSongs(albumId: song.albumId)) ?? [])
            if let index = songs.firstIndex(where: { $0.id == song.id }) {
                player.play(songs: songs, startIndex: index)
            } else {
                player.playSong(song)
            }
        }
    }

    private func initialLoad() async {
        if topPicks.isEmpty {
            topPicks = client.cacheRead("toppicks.json") ?? []
            recentSongs = client.cacheRead("recent_songs.json") ?? []
            mixes = client.cacheRead("mixes.json") ?? []
        }
        let hasData = !topPicks.isEmpty || !recentSongs.isEmpty || !mixes.isEmpty
        if hasData && !SessionRefresh.claim("home") { return }
        await load(force: false)
    }

    private func load(force: Bool) async {
        async let picks = client.topPicks()
        async let recents = client.recentlyPlayed()
        async let mixResults = client.madeForYou(force: force)
        let newPicks = (try? await picks) ?? []
        let newRecents = (try? await recents) ?? []
        let newMixes = await mixResults
        if !newPicks.isEmpty { topPicks = newPicks }
        if !newRecents.isEmpty { recentSongs = newRecents }
        if !newMixes.isEmpty { mixes = newMixes }
    }
}

// MARK: - Library

struct LibraryView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var pins: PinStore
    @EnvironmentObject private var router: Router

    @State private var recentlyAdded: [Album] = []

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        NavigationStack(path: $router.libraryPath) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if !pins.pins.isEmpty {
                        SectionHeader(text: "Pinned")
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(pins.pins) { pin in
                                    PinnedCard(pin: pin)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                    VStack(spacing: 0) {
                        libraryLink("Playlists", icon: "music.note.list", route: .playlists)
                        Divider()
                        libraryLink("Artists", icon: "person.fill", route: .artists)
                        Divider()
                        libraryLink("Albums", icon: "square.stack", route: .albums)
                        Divider()
                        libraryLink("Songs", icon: "music.note", route: .songs)
                    }
                    SectionHeader(text: "Recently Added")
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(recentlyAdded) { album in
                            Button {
                                router.libraryPath.append(Route.album(album.id))
                            } label: {
                                AlbumCard(album: album)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }
                .padding(.vertical)
            }
            .navigationTitle("Library")
            .navigationDestination(for: Route.self) { RouteDestination(route: $0) }
            .refreshable {
                recentlyAdded = (try? await client.recentlyAdded()) ?? recentlyAdded
            }
            .task {
                if recentlyAdded.isEmpty {
                    recentlyAdded = client.cacheRead("recent_albums.json") ?? []
                }
                if !recentlyAdded.isEmpty && !SessionRefresh.claim("library") { return }
                recentlyAdded = (try? await client.recentlyAdded()) ?? recentlyAdded
            }
        }
    }

    private func libraryLink(_ title: String, icon: String, route: Route) -> some View {
        Button {
            router.libraryPath.append(route)
        } label: {
            HStack {
                Image(systemName: icon).foregroundColor(.accentColor).frame(width: 28)
                Text(title).font(.headline).foregroundColor(.primary)
                Spacer()
                Image(systemName: "chevron.right").foregroundColor(.secondary)
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
        }
    }
}

struct PinnedCard: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var pins: PinStore
    @EnvironmentObject private var router: Router

    let pin: PinnedItem

    var body: some View {
        Button {
            if pin.kind == "song" {
                let client = client
                let player = player
                let pin = pin
                Task {
                    let songs = sortedByAlbumOrder((try? await client.albumSongs(albumId: pin.id)) ?? [])
                    if let index = songs.firstIndex(where: { $0.id == pin.songId }) {
                        player.play(songs: songs, startIndex: index)
                    }
                }
            } else {
                router.libraryPath.append(Route.album(pin.id))
            }
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                CoverArt(url: client.imageURL(albumId: pin.id), size: 120, corner: 10)
                Text(pin.title).font(.subheadline).bold().lineLimit(1)
                if !pin.subtitle.isEmpty {
                    Text(pin.subtitle).font(.caption).foregroundColor(.secondary).lineLimit(1)
                }
            }
            .frame(width: 120, alignment: .leading)
        }
        .buttonStyle(.plain)
        .contextMenu {
            if pin.kind == "song", !pin.id.isEmpty {
                Button {
                    router.libraryPath.append(Route.album(pin.id))
                } label: {
                    Label("Go to Album", systemImage: "square.stack")
                }
            }
            Button(role: .destructive) {
                pins.remove(pin)
            } label: {
                Label("Unpin", systemImage: "pin.slash")
            }
        }
    }
}

// MARK: - Albums grid

struct AlbumsView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var router: Router

    @State private var albums: [Album] = []

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(albums) { album in
                    Button {
                        router.libraryPath.append(Route.album(album.id))
                    } label: {
                        AlbumCard(album: album)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .navigationTitle("Albums")
        .refreshable {
            albums = (try? await client.albums()) ?? albums
        }
        .task {
            if albums.isEmpty {
                albums = client.cacheRead("albums.json") ?? []
            }
            if !albums.isEmpty && !SessionRefresh.claim("albums") { return }
            albums = (try? await client.albums()) ?? albums
        }
    }
}

// MARK: - Album detail

struct AlbumView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager

    let albumId: String
    @State private var songs: [Song] = []

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    CoverArt(url: client.imageURL(albumId: albumId), size: 180, corner: 12)
                    if let first = songs.first {
                        Text(first.albumName.isEmpty ? "Album" : first.albumName)
                            .font(.title3).bold()
                            .multilineTextAlignment(.center)
                        Text(first.artist).font(.subheadline).foregroundColor(.secondary)
                    }
                    PlayShuffleRow(
                        onPlay: { player.play(songs: songs) },
                        onShuffle: { player.play(songs: songs.shuffled()) }
                    )
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)
            }
            Section {
                ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                    HStack(spacing: 10) {
                        Text("\(song.trackNumber > 0 ? song.trackNumber : index + 1)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .frame(width: 24)
                        SongRow(song: song, onTap: {
                            player.play(songs: songs, startIndex: index)
                        }, onOpenAlbum: nil, onRemoveFromPlaylist: nil)
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Album")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            songs = sortedByAlbumOrder((try? await client.albumSongs(albumId: albumId)) ?? songs)
        }
        .task {
            if songs.isEmpty {
                songs = sortedByAlbumOrder(client.cacheRead("album_\(albumId).json") ?? [])
            }
            songs = sortedByAlbumOrder((try? await client.albumSongs(albumId: albumId)) ?? songs)
        }
    }
}

// MARK: - Artists

struct ArtistsView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var router: Router

    @State private var artists: [ArtistEntry] = []

    var body: some View {
        List(artists) { artist in
            Button {
                router.libraryPath.append(Route.artist(artist.name))
            } label: {
                HStack(spacing: 12) {
                    CoverArt(url: client.imageURL(albumId: artist.coverAlbumId), size: 48, corner: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(artist.name).font(.subheadline).bold().foregroundColor(.primary)
                        Text(artist.albumCount == 1 ? "1 album" : "\(artist.albumCount) albums")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundColor(.secondary)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Artists")
        .refreshable {
            let albums = (try? await client.albums()) ?? []
            if !albums.isEmpty { artists = artistsFromAlbums(albums) }
        }
        .task {
            if artists.isEmpty {
                artists = artistsFromAlbums(client.cacheRead("albums.json") ?? [])
            }
            if !artists.isEmpty && !SessionRefresh.claim("artists") { return }
            let albums = (try? await client.albums()) ?? []
            if !albums.isEmpty { artists = artistsFromAlbums(albums) }
        }
    }
}

struct ArtistDetailView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var router: Router

    let artistName: String
    @State private var albums: [Album] = []

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(albums) { album in
                    Button {
                        router.libraryPath.append(Route.album(album.id))
                    } label: {
                        AlbumCard(album: album)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .navigationTitle(artistName)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            var all: [Album] = client.cacheRead("albums.json") ?? []
            if all.isEmpty {
                all = (try? await client.albums()) ?? []
            }
            albums = all.filter {
                let name = $0.artist.trimmingCharacters(in: .whitespaces)
                return (name.isEmpty ? "Unknown Artist" : name) == artistName
            }
        }
    }
}

// MARK: - Songs (paged)

struct SongsView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var router: Router

    @State private var songs: [Song] = []
    @State private var total = 0
    @State private var loadingMore = false

    var body: some View {
        List {
            Section {
                PlayShuffleRow(
                    onPlay: { player.play(songs: Array(songs.prefix(50))) },
                    onShuffle: { player.play(songs: Array(songs.shuffled().prefix(50))) }
                )
                .listRowSeparator(.hidden)
                Text(songs.count < total ? "\(songs.count) of \(total) songs" : "\(songs.count) songs")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .listRowSeparator(.hidden)
            }
            Section {
                ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                    SongRow(song: song, onTap: {
                        player.play(songs: Array(songs.suffix(from: index).prefix(50)))
                    }, onOpenAlbum: { router.libraryPath.append(Route.album($0)) }, onRemoveFromPlaylist: nil)
                    .onAppear {
                        if index >= songs.count - 8 {
                            loadMore()
                        }
                    }
                }
                if loadingMore {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Songs")
        .refreshable {
            if let page = try? await client.songsPage(startIndex: 0) {
                songs = page.songs
                total = page.total
            }
        }
        .task {
            if songs.isEmpty {
                songs = client.cacheRead("songs_page0.json") ?? []
                total = max(songs.count, client.cacheRead("songs_total.json") ?? 0)
            }
            if !songs.isEmpty && !SessionRefresh.claim("songs") { return }
            if let page = try? await client.songsPage(startIndex: 0) {
                if songs.isEmpty || Array(songs.prefix(page.songs.count)) != page.songs {
                    songs = page.songs
                }
                total = page.total
            }
        }
    }

    private func loadMore() {
        guard !loadingMore, songs.count < total, !songs.isEmpty else { return }
        loadingMore = true
        let client = client
        let start = songs.count
        Task {
            defer { loadingMore = false }
            guard let page = try? await client.songsPage(startIndex: start) else { return }
            let known = Set(songs.map(\.id))
            songs.append(contentsOf: page.songs.filter { !known.contains($0.id) })
            total = page.total
        }
    }
}

// MARK: - Playlists

struct PlaylistsView: View {
    @EnvironmentObject private var playlists: PlaylistStore
    @EnvironmentObject private var downloads: DownloadManager
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var router: Router

    @State private var showCreate = false
    @State private var newName = ""

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 16) {
                Button {
                    router.libraryPath.append(Route.playlist("__downloaded__"))
                } label: {
                    playlistCard(
                        name: "Downloaded",
                        subtitle: "\(downloads.completed.count) songs",
                        coverAlbumId: nil,
                        icon: "arrow.down.circle.fill"
                    )
                }
                .buttonStyle(.plain)
                ForEach(playlists.playlists) { playlist in
                    Button {
                        router.libraryPath.append(Route.playlist(playlist.id))
                    } label: {
                        playlistCard(
                            name: playlist.name,
                            subtitle: "\(playlist.entries.count) songs",
                            coverAlbumId: playlist.entries.first?.albumId,
                            icon: "music.note"
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .navigationTitle("Playlists")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    newName = ""
                    showCreate = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .alert("New playlist", isPresented: $showCreate) {
            TextField("Name", text: $newName)
            Button("Create") {
                let id = playlists.create(name: newName)
                router.libraryPath.append(Route.playlist(id))
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    @ViewBuilder
    private func playlistCard(name: String, subtitle: String, coverAlbumId: String?, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let coverAlbumId, !coverAlbumId.isEmpty {
                CoverArtFlexible(url: client.imageURL(albumId: coverAlbumId))
            } else {
                Color.gray.opacity(0.2)
                    .aspectRatio(1, contentMode: .fit)
                    .overlay(Image(systemName: icon).font(.system(size: 44)).foregroundColor(.accentColor))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            Text(name).font(.subheadline).bold().lineLimit(1)
            Text(subtitle).font(.caption).foregroundColor(.secondary).lineLimit(1)
        }
    }
}

struct PlaylistDetailView: View {
    @EnvironmentObject private var playlists: PlaylistStore
    @EnvironmentObject private var downloads: DownloadManager
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss

    let playlistId: String
    @State private var showAddSongs = false

    private var isDownloadedList: Bool { playlistId == "__downloaded__" }

    private var title: String {
        isDownloadedList ? "Downloaded" : (playlists.playlists.first { $0.id == playlistId }?.name ?? "Playlist")
    }

    private var entries: [PlaylistEntry] {
        if isDownloadedList {
            return downloads.completed.map {
                PlaylistEntry(albumId: $0.albumId, songId: $0.songId, title: $0.title, artist: $0.artist)
            }
        }
        return playlists.playlists.first { $0.id == playlistId }?.entries ?? []
    }

    var body: some View {
        List {
            Section {
                PlayShuffleRow(
                    onPlay: { playEntries(entries, startIndex: 0) },
                    onShuffle: { playEntries(entries.shuffled(), startIndex: 0) }
                )
                .listRowSeparator(.hidden)
            }
            Section {
                ForEach(Array(entries.enumerated()), id: \.element.songId) { index, entry in
                    SongRow(
                        song: songFor(entry),
                        onTap: { playEntries(entries, startIndex: index) },
                        onOpenAlbum: { router.libraryPath.append(Route.album($0)) },
                        onRemoveFromPlaylist: isDownloadedList ? nil : {
                            playlists.remove(albumId: entry.albumId, songId: entry.songId, from: playlistId)
                        }
                    )
                }
                .onMove { source, destination in
                    guard !isDownloadedList else { return }
                    var updated = entries
                    updated.move(fromOffsets: source, toOffset: destination)
                    playlists.setEntries(updated, for: playlistId)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !isDownloadedList {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button {
                            showAddSongs = true
                        } label: {
                            Label("Add Songs", systemImage: "plus")
                        }
                        Button {
                            let client = client
                            let downloads = downloads
                            let toDownload = entries
                            Task {
                                for entry in toDownload {
                                    downloads.download(song: songFor(entry), client: client)
                                }
                            }
                        } label: {
                            Label("Download All", systemImage: "arrow.down.circle")
                        }
                        Button(role: .destructive) {
                            playlists.delete(id: playlistId)
                            dismiss()
                        } label: {
                            Label("Delete Playlist", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .sheet(isPresented: $showAddSongs) {
            AddSongsSheet(playlistId: playlistId)
        }
    }

    private func songFor(_ entry: PlaylistEntry) -> Song {
        Song(
            id: entry.songId,
            albumId: entry.albumId,
            title: entry.title,
            artist: entry.artist,
            albumName: "",
            durationSec: 0,
            discNumber: 0,
            trackNumber: 0,
            genres: []
        )
    }

    private func playEntries(_ list: [PlaylistEntry], startIndex: Int) {
        let songs = list.map(songFor)
        player.play(songs: songs, startIndex: startIndex)
    }
}

struct AddSongsSheet: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var playlists: PlaylistStore
    @Environment(\.dismiss) private var dismiss

    let playlistId: String
    @State private var query = ""
    @State private var results: [Song] = []

    var body: some View {
        NavigationStack {
            List(results) { song in
                Button {
                    playlists.add(
                        PlaylistEntry(albumId: song.albumId, songId: song.id, title: song.title, artist: song.artist),
                        to: playlistId
                    )
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(song.title).font(.subheadline).bold().foregroundColor(.primary)
                            Text(song.artist).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        Image(systemName: "plus.circle").foregroundColor(.accentColor)
                    }
                }
            }
            .searchable(text: $query, prompt: "Search songs")
            .onChange(of: query) { term in
                guard term.count >= 2 else { return }
                let client = client
                Task {
                    let found = (try? await client.search(term))?.songs ?? []
                    await MainActor.run { results = found }
                }
            }
            .navigationTitle("Add Songs")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Mix

struct MixView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var router: Router

    let mixId: String
    @State private var mix: Mix?

    var body: some View {
        List {
            if let mix {
                Section {
                    Text(mix.subtitle)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .listRowSeparator(.hidden)
                    PlayShuffleRow(
                        onPlay: { player.play(songs: mix.songs) },
                        onShuffle: { player.play(songs: mix.songs.shuffled()) }
                    )
                    .listRowSeparator(.hidden)
                }
                Section {
                    ForEach(Array(mix.songs.enumerated()), id: \.element.id) { index, song in
                        SongRow(song: song, onTap: {
                            player.play(songs: mix.songs, startIndex: index)
                        }, onOpenAlbum: { router.libraryPath.append(Route.album($0)) }, onRemoveFromPlaylist: nil)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .listStyle(.plain)
        .navigationTitle(mix?.title ?? "Mix")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            mix = await client.mix(id: mixId)
        }
    }
}

// MARK: - Search

struct SearchView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager

    @State private var query = ""
    @State private var albums: [Album] = []
    @State private var songs: [Song] = []
    @State private var artists: [ArtistEntry] = []
    @State private var searching = false
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if searching {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
                if !artists.isEmpty {
                    Section("Artists") {
                        ForEach(artists) { artist in
                            Button {
                                path.append(Route.artist(artist.name))
                            } label: {
                                HStack(spacing: 12) {
                                    CoverArt(url: client.imageURL(albumId: artist.coverAlbumId), size: 44, corner: 22)
                                    VStack(alignment: .leading) {
                                        Text(artist.name).font(.subheadline).bold().foregroundColor(.primary)
                                        Text("Artist • \(artist.albumCount) albums")
                                            .font(.caption).foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
                if !albums.isEmpty {
                    Section("Albums") {
                        ForEach(albums) { album in
                            Button {
                                path.append(Route.album(album.id))
                            } label: {
                                HStack(spacing: 12) {
                                    CoverArt(url: client.imageURL(albumId: album.id), size: 44, corner: 6)
                                    VStack(alignment: .leading) {
                                        Text(album.name).font(.subheadline).bold().foregroundColor(.primary)
                                        Text(album.artist).font(.caption).foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
                if !songs.isEmpty {
                    Section("Songs") {
                        ForEach(songs) { song in
                            SongRow(song: song, onTap: {
                                playFromAlbum(song)
                            }, onOpenAlbum: { path.append(Route.album($0)) }, onRemoveFromPlaylist: nil)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Search")
            .navigationDestination(for: Route.self) { RouteDestination(route: $0) }
            .searchable(text: $query, prompt: "Search albums and songs")
            .onChange(of: query) { term in
                runSearch(term)
            }
        }
    }

    private func playFromAlbum(_ song: Song) {
        let client = client
        let player = player
        Task {
            let albumTracks = sortedByAlbumOrder((try? await client.albumSongs(albumId: song.albumId)) ?? [])
            if let index = albumTracks.firstIndex(where: { $0.id == song.id }) {
                player.play(songs: albumTracks, startIndex: index)
            } else {
                player.playSong(song)
            }
        }
    }

    private func runSearch(_ term: String) {
        let trimmed = term.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= 2 else {
            albums = []
            songs = []
            artists = []
            return
        }
        searching = true
        let client = client
        Task {
            let result = (try? await client.search(trimmed)) ?? ([], [])
            let allAlbums: [Album] = client.cacheRead("albums.json") ?? []
            let artistMatches = artistsFromAlbums(allAlbums)
                .filter { $0.name.localizedCaseInsensitiveContains(trimmed) }
            await MainActor.run {
                albums = result.0
                songs = result.1
                artists = artistMatches
                searching = false
            }
        }
    }
}
