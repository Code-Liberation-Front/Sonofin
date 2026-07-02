package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemExpanded
import app.shelfie.data.LibraryItemSummary
import app.shelfie.data.PodcastEpisode
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class SearchResults(
    val artists: List<ArtistRow>,
    val podcasts: List<LibraryItemSummary>,
    val episodes: List<Pair<LibraryItemExpanded, PodcastEpisode>>,
) {
    val isEmpty: Boolean get() = artists.isEmpty() && podcasts.isEmpty() && episodes.isEmpty()
}

@Composable
fun SearchScreen(
    app: ShelfieApp,
    controller: MediaController?,
    onOpenPodcast: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenArtist: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val completedDownloads by app.downloads.completed.collectAsState()
    val activeDownloads by app.downloads.active.collectAsState()
    val pins by app.pins.pins.collectAsState()
    var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }

    pickerEntry?.let { entry ->
        PlaylistPickerDialog(app = app, entry = entry, onDismiss = { pickerEntry = null })
    }

    LaunchedEffect(query, refreshKey) {
        error = null
        if (query.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        if (refreshKey == 0) delay(400) // debounce typing; refreshes run immediately
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val (podcasts, episodes) = app.repository.search(query)
                // Artist matches come from grouping the album library locally.
                val albums = app.repository.cachedAlbums()
                    .ifEmpty { runCatching { app.repository.podcasts() }.getOrDefault(emptyList()) }
                val artists = artistsFromAlbums(albums)
                    .filter { it.name.contains(query.trim(), ignoreCase = true) }
                SearchResults(artists, podcasts, episodes)
            }
        }
        outcome.fold(
            onSuccess = { fresh -> if (fresh != results) results = fresh },
            onFailure = { error = it.message ?: "Search failed" },
        )
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search albums and songs") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        RefreshablePage(
            refreshing = searching && results != null,
            onRefresh = { if (query.isNotBlank()) refreshKey++ },
        ) {
        when {
            searching && results == null -> {
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            results?.isEmpty == true -> {
                Text(
                    "No matches for \"$query\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            else -> results?.let { found ->
                LazyColumn(Modifier.fillMaxSize()) {
                    if (found.artists.isNotEmpty()) {
                        item { SearchSectionTitle("Artists") }
                        items(found.artists, key = { "a:${it.name}" }) { artist ->
                            ArtistResultRow(
                                artist = artist,
                                coverUrl = app.repository.coverUrl(artist.coverAlbumId),
                                onClick = { onOpenArtist(artist.name) },
                            )
                        }
                    }
                    if (found.podcasts.isNotEmpty()) {
                        item { SearchSectionTitle("Albums") }
                        items(found.podcasts, key = { "p:${it.id}" }) { podcast ->
                            PodcastResultRow(
                                title = podcast.media.metadata.title ?: "Album",
                                author = podcast.media.metadata.author ?: "",
                                coverUrl = app.repository.coverUrl(podcast.id),
                                onClick = { onOpenPodcast(podcast.id) },
                            )
                        }
                    }
                    if (found.episodes.isNotEmpty()) {
                        item { SearchSectionTitle("Songs") }
                        items(found.episodes, key = { "e:${it.second.id}" }) { (podcast, episode) ->
                            val itemId = podcast.id
                            val durationSec = (episode.audioTrack?.duration ?: episode.audioFile?.duration ?: 0.0)
                            val isDownloaded = completedDownloads.any {
                                it.itemId == itemId && it.episodeId == episode.id
                            }
                            EpisodeResultRow(
                                episode = episode,
                                podcastTitle = podcast.media.metadata.title ?: "",
                                coverUrl = app.repository.coverUrl(itemId),
                                downloadUi = downloadUiFor(app, activeDownloads, completedDownloads, itemId, episode.id),
                                actions = EpisodeMenuActions(
                                    isDownloaded = isDownloaded,
                                    isPinned = isSongPinned(pins, itemId, episode.id),
                                    onPlayNext = { controller?.playNext(itemId, episode.id) },
                                    onTogglePin = {
                                        togglePinnedSong(
                                            app, itemId, episode.id,
                                            title = episode.title ?: "Song",
                                            subtitle = podcast.media.metadata.title.orEmpty(),
                                        )
                                    },
                                    onAddToPlaylist = {
                                        pickerEntry = PlaylistEntry(
                                            itemId = itemId,
                                            episodeId = episode.id,
                                            title = episode.title ?: "Song",
                                            podcastTitle = podcast.media.metadata.title ?: "",
                                        )
                                    },
                                    onGoToPodcast = { onOpenPodcast(itemId) },
                                    onToggleDownload = {
                                        toggleEpisodeDownload(app, scope, itemId, episode.id, isDownloaded)
                                    },
                                ),
                                onClick = { controller?.playEpisode(itemId, episode.id) },
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
private fun SearchSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun ArtistResultRow(artist: ArtistRow, coverUrl: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CoverImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.CircleShape),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                artist.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (artist.albumCount == 1) "Artist • 1 album" else "Artist • ${artist.albumCount} albums",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PodcastResultRow(title: String, author: String, coverUrl: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CoverImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (author.isNotBlank()) {
                Text(
                    author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EpisodeResultRow(
    episode: PodcastEpisode,
    podcastTitle: String,
    coverUrl: String,
    downloadUi: DownloadUi,
    actions: EpisodeMenuActions,
    onClick: () -> Unit,
) {
    val durationSec = (episode.audioTrack?.duration ?: episode.audioFile?.duration ?: 0.0).toLong()
    val dateLine = listOf(
        formatEpisodeDate(episode.publishedAt, episode.pubDate),
        formatDuration(durationSec),
    )
        .filter { it.isNotBlank() }
        .joinToString(" • ")
    EpisodeLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            EpisodeRowContent(
                coverUrl = coverUrl,
                title = episode.title ?: "Song",
                subtitle = podcastTitle,
                dateLine = dateLine,
                progressFraction = 0f,
                completed = false,
                downloadUi = downloadUi,
            )
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}
