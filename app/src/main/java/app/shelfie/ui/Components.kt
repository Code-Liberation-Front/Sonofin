package app.shelfie.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemSummary
import app.shelfie.data.PodcastEpisode
import app.shelfie.download.ActiveDownload
import app.shelfie.download.DownloadedEpisode
import app.shelfie.pin.PinnedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared cover + text block for every episode list row (Latest, Playlist,
 * podcast detail, Search), so they all look identical: square cover, a title
 * that wraps onto further lines until it fits, then the podcast name and the
 * publish date/time each on their own line, and a thin progress bar.
 *
 * Call inside a [androidx.compose.foundation.layout.Row].
 */
@Composable
fun RowScope.EpisodeRowContent(
    coverUrl: String,
    title: String,
    subtitle: String?,
    dateLine: String,
    progressFraction: Float,
    completed: Boolean,
    titleColor: Color = Color.Unspecified,
    downloadUi: DownloadUi = DownloadUi.None,
) {
    CoverImage(
        model = coverUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        completed = completed,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
    Column(
        Modifier
            .weight(1f)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = titleColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (dateLine.isNotBlank()) {
            Text(
                dateLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (val d = downloadUi) {
            is DownloadUi.InProgress -> {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.fraction > 0f) {
                        CircularProgressIndicator(
                            progress = { d.fraction },
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (d.fraction > 0f) "Downloading ${(d.fraction * 100).toInt()}%" else "Downloading…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            DownloadUi.Done -> {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.DownloadDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            DownloadUi.None -> {}
        }
        if (progressFraction > 0.01f && !completed) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
    }
}

/** Options shown in a song's long-press context menu. */
class EpisodeMenuActions(
    val isDownloaded: Boolean,
    val onAddToPlaylist: () -> Unit,
    /** When null, the "Go to podcast" entry is hidden (e.g. already on it). */
    val onGoToPodcast: (() -> Unit)?,
    val onToggleDownload: () -> Unit,
    /** When non-null, a "Remove from playlist" entry is shown (playlist screen). */
    val onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Whether the song is pinned to the Library tab. */
    val isPinned: Boolean = false,
    /** When non-null, a "Pin/Unpin" entry is shown. */
    val onTogglePin: (() -> Unit)? = null,
    /** When non-null, a "Play next" entry is shown. */
    val onPlayNext: (() -> Unit)? = null,
)

/**
 * Wraps an episode element so a normal tap runs [onClick] and a long-press
 * opens a context menu of [actions].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeLongPressBox(
    onClick: () -> Unit,
    actions: EpisodeMenuActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Box(
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
        ) {
            content()
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(x = 8.dp, y = 0.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            actions.onPlayNext?.let { playNext ->
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = { Icon(Icons.Filled.QueueMusic, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        playNext()
                    },
                )
            }
            actions.onTogglePin?.let { togglePin ->
                DropdownMenuItem(
                    text = { Text(if (actions.isPinned) "Unpin" else "Pin") },
                    leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        togglePin()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.onAddToPlaylist()
                },
            )
            actions.onGoToPodcast?.let { goToPodcast ->
                DropdownMenuItem(
                    text = { Text("Go to album") },
                    leadingIcon = { Icon(Icons.Filled.Podcasts, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        goToPodcast()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (actions.isDownloaded) "Remove download" else "Download") },
                leadingIcon = {
                    Icon(
                        if (actions.isDownloaded) Icons.Filled.DeleteOutline else Icons.Filled.Download,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    actions.onToggleDownload()
                },
            )
            actions.onRemoveFromPlaylist?.let { remove ->
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    leadingIcon = { Icon(Icons.Filled.PlaylistRemove, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        remove()
                    },
                )
            }
        }
    }
}

/**
 * Header bar for multi-select episode lists: a "Select" entry button, and in
 * select mode a count, Select-all/None toggle, and bulk download / add-to-playlist.
 */
@Composable
fun SelectionBar(
    selectMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onEnter: () -> Unit,
    onCancel: () -> Unit,
    onToggleAll: () -> Unit,
    onBulkPlaylist: () -> Unit,
    onBulkDownload: () -> Unit,
) {
    if (!selectMode) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = onEnter) {
                Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Select")
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
            }
            Text("$selectedCount selected", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleAll) {
                Text(if (allSelected) "None" else "All")
            }
            IconButton(onClick = onBulkPlaylist, enabled = selectedCount > 0) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = "Add selected to playlist")
            }
            IconButton(onClick = onBulkDownload, enabled = selectedCount > 0) {
                Icon(Icons.Filled.Download, contentDescription = "Download selected")
            }
        }
    }
}

/** Downloads a batch of episodes for offline use. */
fun bulkDownload(
    app: ShelfieApp,
    scope: CoroutineScope,
    episodes: List<PodcastEpisode>,
) {
    scope.launch(Dispatchers.IO) {
        episodes.groupBy { it.libraryItemId }.forEach { (itemId, eps) ->
            runCatching {
                val podcast = app.repository.podcast(itemId)
                eps.forEach { ep ->
                    podcast.media.episodes.firstOrNull { it.id == ep.id }
                        ?.let { app.downloads.download(podcast, it) }
                }
            }
        }
    }
}

/** Current download state for an episode, from the active/completed flows. */
fun downloadUiFor(
    app: ShelfieApp,
    active: Map<String, ActiveDownload>,
    completed: List<DownloadedEpisode>,
    itemId: String,
    episodeId: String,
): DownloadUi {
    val key = app.downloads.key(itemId, episodeId)
    return when {
        completed.any { it.itemId == itemId && it.episodeId == episodeId } -> DownloadUi.Done
        active.containsKey(key) -> DownloadUi.InProgress(active[key]?.fraction ?: 0f)
        else -> DownloadUi.None
    }
}

/** Downloads a batch of episodes given their (itemId, episodeId) pairs. */
fun bulkDownloadByIds(
    app: ShelfieApp,
    scope: CoroutineScope,
    items: List<Pair<String, String>>,
) {
    scope.launch(Dispatchers.IO) {
        items.groupBy { it.first }.forEach { (itemId, pairs) ->
            runCatching {
                val podcast = app.repository.podcast(itemId)
                pairs.forEach { (_, episodeId) ->
                    podcast.media.episodes.firstOrNull { it.id == episodeId }
                        ?.let { app.downloads.download(podcast, it) }
                }
            }
        }
    }
}

/** Options shown in an album card's long-press context menu. */
class AlbumMenuActions(
    val isPinned: Boolean,
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val onTogglePin: () -> Unit,
    val onDownloadAll: () -> Unit,
)

/**
 * Wraps an album card so a tap runs [onClick] and a long-press opens the
 * album context menu (play, shuffle, pin, download).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumLongPressBox(
    onClick: () -> Unit,
    actions: AlbumMenuActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Box(
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
        ) {
            content()
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(x = 8.dp, y = 0.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Play") },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.onPlay()
                },
            )
            DropdownMenuItem(
                text = { Text("Shuffle") },
                leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.onShuffle()
                },
            )
            DropdownMenuItem(
                text = { Text(if (actions.isPinned) "Unpin" else "Pin") },
                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text("Download album") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.onDownloadAll()
                },
            )
        }
    }
}

/** Album order: disc number, then track number, then title. */
fun List<PodcastEpisode>.sortedByAlbumOrder(): List<PodcastEpisode> = sortedWith(
    compareBy(
        { it.season?.toIntOrNull() ?: 0 },
        { it.episode?.toIntOrNull() ?: Int.MAX_VALUE },
        { it.title.orEmpty() },
    ),
)

/** Fetches an album's songs and plays them (optionally shuffled). */
fun playAlbum(
    app: ShelfieApp,
    scope: CoroutineScope,
    controller: MediaController?,
    itemId: String,
    shuffled: Boolean,
) {
    if (controller == null) return
    scope.launch {
        val songs = withContext(Dispatchers.IO) {
            runCatching { app.repository.podcast(itemId).media.episodes }.getOrDefault(emptyList())
        }.sortedByAlbumOrder()
        // MediaController must be used from the main thread (this scope).
        controller.playSongs(if (shuffled) songs.shuffled() else songs)
    }
}

/** Downloads every song on an album for offline use. */
fun downloadAlbum(app: ShelfieApp, scope: CoroutineScope, itemId: String) {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val album = app.repository.podcast(itemId)
            album.media.episodes.forEach { app.downloads.download(album, it) }
        }
    }
}

/** Builds the standard album context-menu actions for an album card. */
fun albumMenuActions(
    app: ShelfieApp,
    scope: CoroutineScope,
    controller: MediaController?,
    pins: List<PinnedItem>,
    album: LibraryItemSummary,
): AlbumMenuActions = AlbumMenuActions(
    isPinned = isAlbumPinned(pins, album.id),
    onPlay = { playAlbum(app, scope, controller, album.id, shuffled = false) },
    onShuffle = { playAlbum(app, scope, controller, album.id, shuffled = true) },
    onTogglePin = {
        togglePinnedAlbum(
            app,
            album.id,
            title = album.media.metadata.title ?: "Album",
            subtitle = album.media.metadata.displayAuthor.orEmpty(),
        )
    },
    onDownloadAll = { downloadAlbum(app, scope, album.id) },
)

/** Pins an album to the Library tab, or unpins it. */
fun togglePinnedAlbum(app: ShelfieApp, itemId: String, title: String, subtitle: String) {
    app.pins.toggle(PinnedItem(kind = "album", id = itemId, title = title, subtitle = subtitle))
}

/** Whether an album is pinned, given the collected pin list. */
fun isAlbumPinned(pins: List<PinnedItem>, itemId: String): Boolean =
    pins.any { it.kind == "album" && it.id == itemId }

/** Pins a song to the Library tab, or unpins it if already pinned. */
fun togglePinnedSong(
    app: ShelfieApp,
    itemId: String,
    episodeId: String,
    title: String,
    subtitle: String,
) {
    app.pins.toggle(
        PinnedItem(
            kind = "song",
            id = itemId,
            songId = episodeId,
            title = title,
            subtitle = subtitle,
        ),
    )
}

/** Whether a song is pinned, given the collected pin list. */
fun isSongPinned(pins: List<PinnedItem>, itemId: String, episodeId: String): Boolean =
    pins.any { it.kind == "song" && it.id == itemId && it.songId == episodeId }

/** Downloads an episode for offline use, or removes the local copy. */
fun toggleEpisodeDownload(
    app: ShelfieApp,
    scope: CoroutineScope,
    itemId: String,
    episodeId: String,
    isDownloaded: Boolean,
) {
    if (isDownloaded) {
        app.downloads.completed.value
            .firstOrNull { it.itemId == itemId && it.episodeId == episodeId }
            ?.let { app.downloads.delete(it) }
    } else {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val podcast = app.repository.podcast(itemId)
                podcast.media.episodes.firstOrNull { it.id == episodeId }
                    ?.let { app.downloads.download(podcast, it) }
            }
        }
    }
}
