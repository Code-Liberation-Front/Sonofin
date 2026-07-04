import SwiftUI

@main
struct SonofinApp: App {
    @StateObject private var client: JellyfinClient
    @StateObject private var pins: PinStore
    @StateObject private var playlists: PlaylistStore
    @StateObject private var historyStore: HistoryStore
    @StateObject private var downloads: DownloadManager
    @StateObject private var player: PlayerManager
    @StateObject private var router = Router()

    init() {
        let clientObject = JellyfinClient()
        let historyObject = HistoryStore()
        let downloadsObject = DownloadManager()
        _client = StateObject(wrappedValue: clientObject)
        _pins = StateObject(wrappedValue: PinStore())
        _playlists = StateObject(wrappedValue: PlaylistStore())
        _historyStore = StateObject(wrappedValue: historyObject)
        _downloads = StateObject(wrappedValue: downloadsObject)
        _player = StateObject(wrappedValue: PlayerManager(client: clientObject, history: historyObject, downloads: downloadsObject))
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if client.isLoggedIn {
                    RootView()
                } else {
                    LoginView()
                }
            }
            .environmentObject(client)
            .environmentObject(pins)
            .environmentObject(playlists)
            .environmentObject(historyStore)
            .environmentObject(downloads)
            .environmentObject(player)
            .environmentObject(router)
            .tint(Color(red: 0.66, green: 0.33, blue: 0.97))
            .preferredColorScheme(.dark)
        }
    }
}

// MARK: - Root tabs + mini player

struct RootView: View {
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var router: Router
    @State private var playerPresented = false

    var body: some View {
        TabView(selection: $router.tab) {
            HomeView()
                .withMiniPlayer { playerPresented = true }
                .tabItem { Label("Home", systemImage: "house.fill") }
                .tag(0)
            LibraryView()
                .withMiniPlayer { playerPresented = true }
                .tabItem { Label("Library", systemImage: "music.note.list") }
                .tag(1)
            SearchView()
                .withMiniPlayer { playerPresented = true }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(2)
        }
        .fullScreenCover(isPresented: $playerPresented) {
            PlayerView()
        }
    }
}

extension View {
    /// Docks the mini player above the tab bar: applied inside each tab so
    /// the system stacks it on top of the tab bar on every device, and tab
    /// content automatically avoids it.
    func withMiniPlayer(onExpand: @escaping () -> Void) -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) {
            MiniPlayerBar(onExpand: onExpand)
        }
    }
}

/// Apple Music-style floating capsule: cover, title, play/pause, next.
struct MiniPlayerBar: View {
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var client: JellyfinClient
    var onExpand: () -> Void

    var body: some View {
        if let song = player.currentSong {
            HStack(spacing: 12) {
                CoverArt(url: client.imageURL(albumId: song.albumId), size: 38, corner: 8)
                Text(song.title)
                    .font(.subheadline).bold()
                    .lineLimit(1)
                Spacer(minLength: 8)
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .foregroundColor(.primary)
                }
                Button { player.next() } label: {
                    Image(systemName: "forward.fill")
                        .font(.body)
                        .foregroundColor(.primary)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .shadow(color: .black.opacity(0.25), radius: 8, y: 2)
            .padding(.horizontal, 10)
            .padding(.bottom, 4)
            .contentShape(Rectangle())
            .onTapGesture { onExpand() }
        }
    }
}

// MARK: - Login

struct LoginView: View {
    @EnvironmentObject private var client: JellyfinClient
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("Sonofin").font(.largeTitle).bold().foregroundColor(.accentColor)
            Text("Connect to your Jellyfin music server")
                .font(.subheadline)
                .foregroundColor(.secondary)
            TextField("Server URL (https://jellyfin.example.com)", text: $server)
                .textContentType(.URL)
                .keyboardType(.URL)
                .autocapitalization(.none)
                .disableAutocorrection(true)
                .textFieldStyle(.roundedBorder)
            TextField("Username", text: $username)
                .autocapitalization(.none)
                .disableAutocorrection(true)
                .textFieldStyle(.roundedBorder)
            SecureField("Password", text: $password)
                .textFieldStyle(.roundedBorder)
            if let error {
                Text(error).font(.footnote).foregroundColor(.red)
            }
            Button {
                busy = true
                error = nil
                Task {
                    do {
                        try await client.login(server: server, username: username, password: password)
                    } catch {
                        self.error = error.localizedDescription
                    }
                    busy = false
                }
            } label: {
                if busy {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text("Sign in").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(server.isEmpty || username.isEmpty || busy)
            Spacer()
        }
        .padding(24)
    }
}

// MARK: - Settings

struct SettingsView: View {
    @EnvironmentObject private var client: JellyfinClient
    @EnvironmentObject private var player: PlayerManager
    @State private var libraries: [(id: String, name: String)] = []
    @State private var autoplay = true
    @State private var selectedLibrary = ""

    var body: some View {
        List {
            Section("Account") {
                LabeledContent("Server", value: client.server)
                LabeledContent("User", value: client.username)
            }
            Section("Playback") {
                Toggle("Auto play", isOn: $autoplay)
                    .onChange(of: autoplay) { newValue in
                        player.autoplayRandom = newValue
                    }
                Text("Keep playing random songs when the queue ends")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            if libraries.count > 1 {
                Section("Library") {
                    ForEach(libraries, id: \.id) { library in
                        Button {
                            client.selectLibrary(id: library.id)
                            selectedLibrary = library.id
                        } label: {
                            HStack {
                                Text(library.name).foregroundColor(.primary)
                                Spacer()
                                if selectedLibrary == library.id {
                                    Image(systemName: "checkmark").foregroundColor(.accentColor)
                                }
                            }
                        }
                    }
                }
            }
            Section {
                Button("Sign out", role: .destructive) {
                    client.logout()
                }
            }
        }
        .navigationTitle("Settings")
        .task {
            autoplay = player.autoplayRandom
            selectedLibrary = client.libraryId
            libraries = (try? await client.libraries()) ?? []
        }
    }
}

// MARK: - Shared cover art view

struct CoverArt: View {
    var url: URL?
    var size: CGFloat
    var corner: CGFloat

    var body: some View {
        AsyncImage(url: url) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                ZStack {
                    Rectangle().fill(Color.gray.opacity(0.25))
                    Image(systemName: "music.note").foregroundColor(.secondary)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: corner))
    }
}

/// Flexible-width square cover for grids.
struct CoverArtFlexible: View {
    var url: URL?
    var corner: CGFloat = 10

    var body: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay(
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        ZStack {
                            Rectangle().fill(Color.gray.opacity(0.25))
                            Image(systemName: "music.note").foregroundColor(.secondary)
                        }
                    }
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: corner))
    }
}
