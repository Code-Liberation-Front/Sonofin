package app.shelfie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.AbsRepository
import app.shelfie.data.LibraryItemSummary
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private data class HomeShelves(
    val topPicks: List<LibraryItemSummary>,
    val inProgress: List<AbsRepository.InProgressEpisode>,
    val mixes: List<AbsRepository.Mix>,
) {
    val isEmpty: Boolean get() = topPicks.isEmpty() && inProgress.isEmpty() && mixes.isEmpty()
}

@Composable
fun HomeScreen(
    app: ShelfieApp,
    controller: MediaController?,
    onOpenPodcast: (String) -> Unit,
    onOpenMix: (String) -> Unit,
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val progressRevision by app.repository.progressRevision.collectAsState()
    val shelves = rememberServerData(
        refetchKey = refreshKey to progressRevision,
        cached = {
            HomeShelves(
                topPicks = app.repository.cachedTopPicks(),
                inProgress = app.repository.cachedContinueListening(),
                mixes = app.repository.cachedMixes(),
            ).takeUnless { it.isEmpty }
        },
        fetch = {
            if (!app.repository.ensureConfigured()) throw IllegalStateException("Not logged in")
            // The three shelves are independent; fetch them in parallel. Mixes
            // are locally generated, so they only regenerate on an explicit
            // pull-to-refresh — otherwise they stay stable for the day.
            coroutineScope {
                val topPicks = async {
                    runCatching { app.repository.topPicks(forceRefresh = true) }
                        .getOrDefault(emptyList())
                }
                val inProgress = async {
                    runCatching { app.repository.continueListening(limit = 12, forceRefresh = true) }
                        .getOrDefault(emptyList())
                }
                val mixes = async {
                    runCatching { app.repository.madeForYou(forceRefresh = refreshKey > 0) }
                        .getOrDefault(emptyList())
                }
                HomeShelves(topPicks.await(), inProgress.await(), mixes.await())
            }
        },
    )

    RefreshablePage(
        refreshing = shelves.refreshing,
        onRefresh = { refreshKey++ },
    ) {
        HomeContent(app, controller, onOpenPodcast, onOpenMix, shelves)
    }
}

@Composable
private fun HomeContent(
    app: ShelfieApp,
    controller: MediaController?,
    onOpenPodcast: (String) -> Unit,
    onOpenMix: (String) -> Unit,
    shelves: ServerDataState<HomeShelves>,
) {
    when {
        shelves.data == null && shelves.error != null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(shelves.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        }

        shelves.data == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        else -> {
            val state = shelves.data ?: return
            val scope = rememberCoroutineScope()
            val completedDownloads by app.downloads.completed.collectAsState()
            val pins by app.pins.pins.collectAsState()
            var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }

            pickerEntry?.let { entry ->
                PlaylistPickerDialog(app = app, entry = entry, onDismiss = { pickerEntry = null })
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item {
                    Text(
                        "Home",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item { SectionTitle("Top Picks for You") }
                item {
                    if (state.topPicks.isEmpty()) {
                        EmptyHint("Play some music and your favorites will show up here.")
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.topPicks, key = { it.id }) { album ->
                                TopPickCard(
                                    album = album,
                                    coverUrl = app.repository.coverUrl(album.id),
                                    onClick = { onOpenPodcast(album.id) },
                                    actions = albumMenuActions(app, scope, controller, pins, album),
                                )
                            }
                        }
                    }
                }
                item { SectionTitle("Recently Played") }
                item {
                    if (state.inProgress.isEmpty()) {
                        EmptyHint("Nothing played yet — pick an album and start listening.")
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.inProgress, key = { it.episode.id }) { entry ->
                                val itemId = entry.podcast.id
                                val episodeId = entry.episode.id
                                val durationSec = (entry.episode.audioTrack?.duration
                                    ?: entry.episode.audioFile?.duration ?: 0.0)
                                val isDownloaded = completedDownloads.any {
                                    it.itemId == itemId && it.episodeId == episodeId
                                }
                                ContinueCard(
                                    entry = entry,
                                    coverUrl = app.repository.coverUrl(itemId),
                                    actions = EpisodeMenuActions(
                                        isFinished = false,
                                        isDownloaded = isDownloaded,
                                        isPinned = isSongPinned(pins, itemId, episodeId),
                                        onPlayNext = { controller?.playNext(itemId, episodeId) },
                                        onTogglePin = {
                                            togglePinnedSong(
                                                app, itemId, episodeId,
                                                title = entry.episode.title ?: "Song",
                                                subtitle = entry.podcast.media.metadata.title.orEmpty(),
                                            )
                                        },
                                        onResetProgress = {
                                            resetEpisodeProgress(app, scope, itemId, episodeId, durationSec)
                                        },
                                        onToggleFinished = {
                                            setEpisodeFinished(app, scope, itemId, episodeId, finished = true, durationSec = durationSec)
                                        },
                                        onAddToPlaylist = {
                                            pickerEntry = PlaylistEntry(
                                                itemId = itemId,
                                                episodeId = episodeId,
                                                title = entry.episode.title ?: "Song",
                                                podcastTitle = entry.podcast.media.metadata.title ?: "",
                                            )
                                        },
                                        onGoToPodcast = { onOpenPodcast(itemId) },
                                        onToggleDownload = {
                                            toggleEpisodeDownload(app, scope, itemId, episodeId, isDownloaded)
                                        },
                                    ),
                                    onClick = { controller?.playEpisode(itemId, episodeId) },
                                )
                            }
                        }
                    }
                }
                if (state.mixes.isNotEmpty()) {
                    item { SectionTitle("Made for You") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.mixes, key = { it.id }) { mix ->
                                MixCard(
                                    mix = mix,
                                    coverUrl = mix.songs.firstOrNull()
                                        ?.libraryItemId?.takeIf { it.isNotBlank() }
                                        ?.let { app.repository.coverUrl(it) },
                                    onClick = { onOpenMix(mix.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ContinueCard(
    entry: AbsRepository.InProgressEpisode,
    coverUrl: String,
    actions: EpisodeMenuActions,
    onClick: () -> Unit,
) {
    val completed = isNearlyComplete(entry.progress.toFloat(), isFinished = false)
    EpisodeLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.width(150.dp)) {
        Column(
            modifier = Modifier.width(150.dp),
        ) {
            CoverImage(
                model = coverUrl,
                contentDescription = entry.episode.title,
                contentScale = ContentScale.Crop,
                completed = completed,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            if (!completed) {
                LinearProgressIndicator(
                    progress = { entry.progress.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp),
                )
            }
            Text(
                entry.episode.title ?: "Song",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
            Text(
                entry.podcast.media.metadata.title ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TopPickCard(
    album: LibraryItemSummary,
    coverUrl: String,
    onClick: () -> Unit,
    actions: AlbumMenuActions,
) {
    AlbumLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.width(180.dp)) {
        Column {
            CoverImage(
            model = coverUrl,
            contentDescription = album.media.metadata.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(
            album.media.metadata.title ?: "Album",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        album.media.metadata.displayAuthor?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
    }
}

/** A "Made for You" mix card: cover with a scrim and the mix title on top. */
@Composable
private fun MixCard(
    mix: AbsRepository.Mix,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            if (coverUrl != null) {
                CoverImage(
                    model = coverUrl,
                    contentDescription = mix.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    ),
            )
            Text(
                mix.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
        }
        Text(
            mix.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
