package app.shelfie.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.shelfie.R
import app.shelfie.ShelfieApp
import app.shelfie.playback.PlaybackService
import app.shelfie.ui.theme.ShelfieTheme
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

// AppCompatActivity (a FragmentActivity) is required for the Cast chooser dialog.
class MainActivity : AppCompatActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)
    private val loginError = mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        val app = application as ShelfieApp
        setContent {
            ShelfieTheme {
                val controller by controllerState
                val error by loginError
                ShelfieRoot(app = app, controller = controller, loginError = error)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (future.isDone && !future.isCancelled) {
                    runCatching { controllerState.value = future.get() }
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onStop() {
        controllerState.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun ShelfieRoot(app: ShelfieApp, controller: MediaController?, loginError: String? = null) {
    val credentials by app.settings.credentials.collectAsState(initial = null)
    val creds = credentials

    when {
        creds == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        !creds.isLoggedIn -> LoginScreen(app, externalError = loginError)

        else -> MainNavigation(app, controller)
    }
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

// Apple Music-style tabs.
private val BOTTOM_TABS = listOf(
    BottomTab("home", "Home", Icons.Filled.Home),
    BottomTab("library", "Library", Icons.Filled.LibraryMusic),
    BottomTab("search", "Search", Icons.Filled.Search),
)

// Pushed pages that keep the app chrome (top bar + tab bar), like Apple Music.
private val LIBRARY_SUB_ROUTES = setOf(
    "playlists",
    "playlist/{playlistId}",
    "artists",
    "artist/{name}",
    "albums",
    "songs",
    "podcast/{itemId}",
)

/** Which bottom tab a route belongs to, or null for full-screen pages. */
private fun tabForRoute(route: String?): String? = when {
    route == null -> null
    BOTTOM_TABS.any { it.route == route } -> route
    route in LIBRARY_SUB_ROUTES -> "library"
    route == "mix/{mixId}" -> "home"
    else -> null
}

@Composable
fun MainNavigation(app: ShelfieApp, controller: MediaController?) {
    val navController = rememberNavController()
    val playerState = rememberPlayerUiState(controller)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selectedTab = tabForRoute(currentRoute)
    val isOnline by rememberIsOnline()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = playerExpanded) { playerExpanded = false }

    Box(Modifier.fillMaxSize()) {
        MainScaffold(
            app = app,
            controller = controller,
            navController = navController,
            playerState = playerState,
            selectedTab = selectedTab,
            isOnline = isOnline,
            onExpandPlayer = { playerExpanded = true },
        )
        // Apple Music-style full-screen player: slides up over everything,
        // including the now-playing bar and bottom navigation.
        AnimatedVisibility(
            visible = playerExpanded,
            enter = slideInVertically(animationSpec = tween(300), initialOffsetY = { it }),
            exit = slideOutVertically(animationSpec = tween(300), targetOffsetY = { it }),
        ) {
            PlayerScreen(
                app = app,
                state = playerState,
                controller = controller,
                onBack = { playerExpanded = false },
                onOpenAlbum = { itemId ->
                    playerExpanded = false
                    navController.navigate("podcast/$itemId")
                },
                onOpenArtist = { name ->
                    playerExpanded = false
                    navController.navigate("artist/${Uri.encode(name)}")
                },
            )
        }
    }
}

@Composable
private fun MainScaffold(
    app: ShelfieApp,
    controller: MediaController?,
    navController: androidx.navigation.NavHostController,
    playerState: PlayerUiState,
    selectedTab: String?,
    isOnline: Boolean,
    onExpandPlayer: () -> Unit,
) {
    // Tabs and library sub-pages keep the app chrome; the album detail and
    // settings/downloads pages go full screen.
    val showChrome = selectedTab != null
    Scaffold(
        topBar = {
            Column {
                if (showChrome) {
                    ShelfieTopBar(
                        onSettings = { navController.navigate("settings") { launchSingleTop = true } },
                    )
                }
                if (!isOnline) {
                    OfflineBanner(padStatusBar = !showChrome)
                }
            }
        },
        bottomBar = {
            // The NavigationBar consumes the gesture-nav inset itself; when it's
            // hidden the now-playing bar must avoid the navigation bar on its own.
            Column(if (showChrome) Modifier else Modifier.navigationBarsPadding()) {
                NowPlayingBar(
                    state = playerState,
                    controller = controller,
                    onExpand = onExpandPlayer,
                )
                if (showChrome) {
                    NavigationBar {
                        BOTTOM_TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // Full-screen pages have no top bar, and a zero-height topBar slot means
        // the Scaffold applies no status-bar inset — pad explicitly. When offline
        // the banner occupies the slot (with its own inset), so skip it then.
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .padding(padding)
                .then(if (!showChrome && isOnline) Modifier.statusBarsPadding() else Modifier),
        ) {
            composable("home") {
                if (!isOnline) {
                    OfflineTabHint()
                } else {
                    HomeScreen(
                        app = app,
                        controller = controller,
                        onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                        onOpenMix = { mixId -> navController.navigate("mix/${Uri.encode(mixId)}") },
                    )
                }
            }
            composable("library") {
                LibraryScreen(
                    app = app,
                    controller = controller,
                    onOpenAlbum = { itemId -> navController.navigate("podcast/$itemId") },
                    onOpenAlbums = { navController.navigate("albums") { launchSingleTop = true } },
                    onOpenArtists = { navController.navigate("artists") { launchSingleTop = true } },
                    onOpenArtist = { name -> navController.navigate("artist/${Uri.encode(name)}") },
                    onOpenSongs = { navController.navigate("songs") { launchSingleTop = true } },
                    onOpenPlaylists = { navController.navigate("playlists") { launchSingleTop = true } },
                )
            }
            composable("search") {
                if (!isOnline) {
                    OfflineTabHint()
                } else {
                    SearchScreen(
                        app = app,
                        controller = controller,
                        onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                        onBack = {},
                        showBack = false,
                        onOpenArtist = { name -> navController.navigate("artist/${Uri.encode(name)}") },
                    )
                }
            }
            composable("albums") {
                PodcastsScreen(
                    app = app,
                    onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                    onBack = { navController.popBackStack() },
                    controller = controller,
                )
            }
            composable("artists") {
                ArtistsScreen(
                    app = app,
                    onBack = { navController.popBackStack() },
                    onOpenArtist = { name -> navController.navigate("artist/${Uri.encode(name)}") },
                )
            }
            composable("artist/{name}") { entry ->
                ArtistDetailScreen(
                    app = app,
                    artistName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { itemId -> navController.navigate("podcast/$itemId") },
                    controller = controller,
                )
            }
            composable("songs") {
                SongsScreen(
                    app = app,
                    controller = controller,
                    playerState = playerState,
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { itemId -> navController.navigate("podcast/$itemId") },
                )
            }
            composable("mix/{mixId}") { entry ->
                MixScreen(
                    app = app,
                    controller = controller,
                    playerState = playerState,
                    mixId = entry.arguments?.getString("mixId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { itemId -> navController.navigate("podcast/$itemId") },
                )
            }
            composable("podcast/{itemId}") { entry ->
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                EpisodesScreen(
                    app = app,
                    itemId = itemId,
                    controller = controller,
                    playerState = playerState,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("playlists") {
                PlaylistsScreen(
                    app = app,
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { id -> navController.navigate("playlist/${Uri.encode(id)}") },
                )
            }
            composable("playlist/{playlistId}") { entry ->
                PlaylistScreen(
                    app = app,
                    controller = controller,
                    playerState = playerState,
                    onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                    playlistId = entry.arguments?.getString("playlistId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    app = app,
                    onOpenDownloads = { navController.navigate("downloads") { launchSingleTop = true } },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    app = app,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun ShelfieTopBar(onSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Sonofin",
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color.White),
        )
        Text(
            "Sonofin",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        CastButton(modifier = Modifier.size(44.dp))
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}
