package app.shelfie.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemSummary
import app.shelfie.pin.PinnedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Apple Music-style Library tab: pinned items, category links, recently added. */
@Composable
fun LibraryScreen(
    app: ShelfieApp,
    controller: MediaController?,
    onOpenAlbum: (String) -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenSongs: () -> Unit,
    onOpenPlaylists: () -> Unit,
) {
    val pins by app.pins.pins.collectAsState()
    val recentlyAdded by produceState(initialValue = emptyList<LibraryItemSummary>()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (app.repository.ensureConfigured()) app.repository.recentlyAdded(12) else emptyList()
            }.getOrDefault(emptyList())
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Text(
                "Library",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (pins.isNotEmpty()) {
            item { LibrarySectionTitle("Pinned") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pins, key = { "${it.kind}:${it.id}:${it.songId}" }) { pin ->
                        PinnedCard(
                            pin = pin,
                            coverUrl = pinCoverUrl(app, pin),
                            onOpen = {
                                when (pin.kind) {
                                    "song" -> controller?.playEpisode(pin.id, pin.songId)
                                    "album" -> onOpenAlbum(pin.id)
                                    "artist" -> onOpenArtist(pin.id)
                                    "playlist" -> onOpenPlaylists()
                                }
                            },
                            onPlayNext = if (pin.kind == "song") {
                                { controller?.playNext(pin.id, pin.songId) }
                            } else {
                                null
                            },
                            onGoToAlbum = if (pin.kind == "song" && pin.id.isNotBlank()) {
                                { onOpenAlbum(pin.id) }
                            } else {
                                null
                            },
                            onUnpin = { app.pins.remove(pin) },
                        )
                    }
                }
            }
        }

        item { LibraryNavRow("Playlists", { Icon(Icons.Filled.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary) }, onOpenPlaylists) }
        item { LibraryNavRow("Artists", { Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.primary) }, onOpenArtists) }
        item { LibraryNavRow("Albums", { Icon(Icons.Filled.Album, null, tint = MaterialTheme.colorScheme.primary) }, onOpenAlbums) }
        item { LibraryNavRow("Songs", { Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary) }, onOpenSongs) }

        if (recentlyAdded.isNotEmpty()) {
            item { LibrarySectionTitle("Recently Added") }
            items(recentlyAdded.chunked(2), key = { it.first().id }) { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    pair.forEach { album ->
                        AlbumGridCard(
                            album = album,
                            coverUrl = app.repository.coverUrl(album.id),
                            onClick = { onOpenAlbum(album.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Cover art for a pinned item; songs and albums use the album id directly. */
private fun pinCoverUrl(app: ShelfieApp, pin: PinnedItem): String? = when (pin.kind) {
    "song", "album" -> pin.id.takeIf { it.isNotBlank() }?.let { app.repository.coverUrl(it) }
    else -> null
}

@Composable
private fun LibrarySectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun LibraryNavRow(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            icon()
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedCard(
    pin: PinnedItem,
    coverUrl: String?,
    onOpen: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)?,
    onUnpin: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(Modifier.width(130.dp)) {
        Column(
            Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
        ) {
            if (coverUrl != null) {
                CoverImage(
                    model = coverUrl,
                    contentDescription = pin.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp)),
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Text(
                pin.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (pin.subtitle.isNotBlank()) {
                Text(
                    pin.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(16.dp),
        ) {
            onPlayNext?.let { playNext ->
                DropdownMenuItem(
                    text = { Text("Play next") },
                    onClick = {
                        menuOpen = false
                        playNext()
                    },
                )
            }
            onGoToAlbum?.let { goToAlbum ->
                DropdownMenuItem(
                    text = { Text("Go to album") },
                    onClick = {
                        menuOpen = false
                        goToAlbum()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Unpin") },
                onClick = {
                    menuOpen = false
                    onUnpin()
                },
            )
        }
    }
}
