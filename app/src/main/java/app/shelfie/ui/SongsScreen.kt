package app.shelfie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.AbsRepository
import app.shelfie.data.PodcastEpisode
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SONGS_PAGE_SIZE = 50

/**
 * Apple Music-style "Songs" page. Large libraries can't be fetched in one
 * request, so this loads a page at a time and fetches the next page as the
 * user scrolls near the end of the list.
 */
@Composable
fun SongsScreen(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var songs by remember { mutableStateOf<List<PodcastEpisode>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    suspend fun loadPage(startIndex: Int) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (!app.repository.ensureConfigured()) throw IllegalStateException("Not logged in")
                app.repository.songsPage(startIndex, SONGS_PAGE_SIZE)
            }
        }
        result.fold(
            onSuccess = { page ->
                if (startIndex == 0) {
                    // Revalidation of the first page: only replace what's shown
                    // when the server actually returned something different.
                    total = page.total
                    if (songs.take(page.songs.size) != page.songs) songs = page.songs
                } else {
                    // Pages can overlap after a refresh; de-dup by song id.
                    songs = (songs + page.songs).distinctBy { it.id }
                    total = page.total
                }
                error = null
            },
            onFailure = { e ->
                if (songs.isEmpty()) error = e.message ?: "Failed to load songs"
            },
        )
    }

    LaunchedEffect(refreshKey) {
        // Paint the persisted first page instantly, then revalidate.
        if (songs.isEmpty()) {
            val cachedFirst = withContext(Dispatchers.IO) {
                runCatching { app.repository.cachedSongsFirstPage() }.getOrDefault(emptyList())
            }
            if (cachedFirst.isNotEmpty()) {
                songs = cachedFirst
                total = cachedFirst.size
                initialLoading = false
            }
        }
        refreshing = true
        loadPage(0)
        refreshing = false
        initialLoading = false
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, songs.size) {
        if (nearEnd && !initialLoading && !loadingMore && songs.isNotEmpty() && songs.size < total) {
            loadingMore = true
            loadPage(songs.size)
            loadingMore = false
        }
    }

    RefreshablePage(
        refreshing = refreshing && !initialLoading,
        onRefresh = { refreshKey++ },
    ) {
        when {
            initialLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            error != null && songs.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }

            else -> {
                val subtitle = if (songs.size < total) {
                    "${songs.size} of $total songs"
                } else {
                    "${songs.size} songs"
                }
                SongListView(
                    app = app,
                    controller = controller,
                    playerState = playerState,
                    title = "Songs",
                    subtitle = subtitle,
                    songs = songs,
                    onBack = onBack,
                    onOpenAlbum = onOpenAlbum,
                    listState = listState,
                    loadingMore = loadingMore,
                )
            }
        }
    }
}

/** A generated "Made for You" mix, played from the Home tab. */
@Composable
fun MixScreen(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
    mixId: String,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val mixState = rememberServerData(
        key = mixId,
        refreshKey = refreshKey,
        cached = { app.repository.cachedMixes().firstOrNull { it.id == mixId } },
        fetch = {
            if (!app.repository.ensureConfigured()) throw IllegalStateException("Not logged in")
            // Only regenerate the mixes on an explicit pull-to-refresh.
            app.repository.madeForYou(forceRefresh = refreshKey > 0).firstOrNull { it.id == mixId }
                ?: throw IllegalStateException("This mix is no longer available")
        },
    )

    RefreshablePage(
        refreshing = mixState.refreshing,
        onRefresh = { refreshKey++ },
    ) {
        val mix = mixState.data
        when {
            mix == null && mixState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(mixState.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }

            mix == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                SongListView(
                    app = app,
                    controller = controller,
                    playerState = playerState,
                    title = mix.title,
                    subtitle = mix.subtitle,
                    songs = mix.songs,
                    onBack = onBack,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        }
    }
}

@Composable
private fun SongListView(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
    title: String,
    subtitle: String,
    songs: List<PodcastEpisode>,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    loadingMore: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val completedDownloads by app.downloads.completed.collectAsState()
    val activeDownloads by app.downloads.active.collectAsState()
    val pins by app.pins.pins.collectAsState()
    var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }

    pickerEntry?.let { entry ->
        PlaylistPickerDialog(app = app, entry = entry, onDismiss = { pickerEntry = null })
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(title, style = MaterialTheme.typography.headlineMedium)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlayShuffleButtons(
                    enabled = songs.isNotEmpty(),
                    onPlay = { controller?.playSongs(songs) },
                    onShuffle = { controller?.playSongs(songs.shuffled()) },
                )
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            val itemId = song.libraryItemId
            val durationSec = (song.audioTrack?.duration ?: song.audioFile?.duration ?: 0.0)
            val isDownloaded = completedDownloads.any {
                it.itemId == itemId && it.episodeId == song.id
            }
            val isCurrent = playerState.mediaId == episodeMediaId(itemId, song.id)
            EpisodeLongPressBox(
                onClick = {
                    controller?.let { c ->
                        if (isCurrent) {
                            if (c.isPlaying) c.pause() else c.play()
                        } else {
                            c.playSongs(songs, index)
                        }
                    }
                },
                actions = EpisodeMenuActions(
                    isFinished = false,
                    isDownloaded = isDownloaded,
                    isPinned = isSongPinned(pins, itemId, song.id),
                    onPlayNext = { controller?.playNext(itemId, song.id) },
                    onTogglePin = {
                        togglePinnedSong(
                            app, itemId, song.id,
                            title = song.title ?: "Song",
                            subtitle = song.subtitle.orEmpty(),
                        )
                    },
                    onResetProgress = {
                        resetEpisodeProgress(app, scope, itemId, song.id, durationSec)
                    },
                    onToggleFinished = {
                        setEpisodeFinished(app, scope, itemId, song.id, finished = true, durationSec = durationSec)
                    },
                    onAddToPlaylist = {
                        pickerEntry = PlaylistEntry(
                            itemId = itemId,
                            episodeId = song.id,
                            title = song.title ?: "Song",
                            podcastTitle = song.subtitle.orEmpty(),
                        )
                    },
                    onGoToPodcast = itemId.takeIf { it.isNotBlank() }?.let { { onOpenAlbum(it) } },
                    onToggleDownload = {
                        toggleEpisodeDownload(app, scope, itemId, song.id, isDownloaded)
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    EpisodeRowContent(
                        coverUrl = app.repository.coverUrl(itemId),
                        title = song.title ?: "Song",
                        subtitle = song.subtitle,
                        dateLine = formatDuration(durationSec.toLong()),
                        progressFraction = 0f,
                        completed = false,
                        titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        downloadUi = downloadUiFor(app, activeDownloads, completedDownloads, itemId, song.id),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
        if (loadingMore) {
            item {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** The Apple Music-style side-by-side Play and Shuffle buttons. */
@Composable
fun PlayShuffleButtons(
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Button(onClick = onPlay, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Play", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = onShuffle, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
