package app.shelfie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import app.shelfie.data.PodcastEpisode
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface SongListUi {
    data object Loading : SongListUi
    data class Error(val message: String) : SongListUi
    data class Ready(val title: String, val subtitle: String, val songs: List<PodcastEpisode>) : SongListUi
}

/** Apple Music-style "Songs" page: every song in the library. */
@Composable
fun SongsScreen(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val ui by produceState<SongListUi>(initialValue = SongListUi.Loading) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.repository.ensureConfigured()) {
                    SongListUi.Error("Not logged in")
                } else {
                    val songs = app.repository.songs()
                    SongListUi.Ready("Songs", "${songs.size} songs", songs)
                }
            } catch (e: Exception) {
                SongListUi.Error(e.message ?: "Failed to load songs")
            }
        }
    }
    SongListContent(app, controller, playerState, ui, onBack, onOpenAlbum)
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
    val ui by produceState<SongListUi>(initialValue = SongListUi.Loading, mixId) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.repository.ensureConfigured()) {
                    SongListUi.Error("Not logged in")
                } else {
                    val mix = app.repository.mix(mixId)
                    if (mix == null) {
                        SongListUi.Error("This mix is no longer available")
                    } else {
                        SongListUi.Ready(mix.title, mix.subtitle, mix.songs)
                    }
                }
            } catch (e: Exception) {
                SongListUi.Error(e.message ?: "Failed to load mix")
            }
        }
    }
    SongListContent(app, controller, playerState, ui, onBack, onOpenAlbum)
}

@Composable
private fun SongListContent(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
    ui: SongListUi,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    when (val state = ui) {
        is SongListUi.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is SongListUi.Error -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }

        is SongListUi.Ready -> {
            val scope = rememberCoroutineScope()
            val completedDownloads by app.downloads.completed.collectAsState()
            val activeDownloads by app.downloads.active.collectAsState()
            val pins by app.pins.pins.collectAsState()
            var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }

            pickerEntry?.let { entry ->
                PlaylistPickerDialog(app = app, entry = entry, onDismiss = { pickerEntry = null })
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                        Text(state.title, style = MaterialTheme.typography.headlineMedium)
                        if (state.subtitle.isNotBlank()) {
                            Text(
                                state.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PlayShuffleButtons(
                            enabled = state.songs.isNotEmpty(),
                            onPlay = { controller?.playSongs(state.songs) },
                            onShuffle = { controller?.playSongs(state.songs.shuffled()) },
                        )
                    }
                }
                itemsIndexed(state.songs, key = { _, s -> s.id }) { index, song ->
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
                                    c.playSongs(state.songs, index)
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
