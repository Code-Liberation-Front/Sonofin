package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ArtistRow(
    val name: String,
    val albumCount: Int,
    /** An album id whose cover stands in for the artist portrait. */
    val coverAlbumId: String,
)

private sealed interface ArtistsUi {
    data object Loading : ArtistsUi
    data class Error(val message: String) : ArtistsUi
    data class Ready(val artists: List<ArtistRow>) : ArtistsUi
}

/** Groups the album library by album artist. */
internal fun artistsFromAlbums(albums: List<LibraryItemSummary>): List<ArtistRow> =
    albums.groupBy { it.media.metadata.displayAuthor?.trim().orEmpty().ifBlank { "Unknown Artist" } }
        .map { (name, items) -> ArtistRow(name, items.size, items.first().id) }
        .sortedBy { it.name.lowercase() }

@Composable
fun ArtistsScreen(
    app: ShelfieApp,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val ui by produceState<ArtistsUi>(initialValue = ArtistsUi.Loading) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.repository.ensureConfigured()) {
                    ArtistsUi.Error("Not logged in")
                } else {
                    ArtistsUi.Ready(artistsFromAlbums(app.repository.podcasts()))
                }
            } catch (e: Exception) {
                ArtistsUi.Error(e.message ?: "Failed to load artists")
            }
        }
    }

    when (val state = ui) {
        is ArtistsUi.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is ArtistsUi.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }

        is ArtistsUi.Ready -> {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                        Text("Artists", style = MaterialTheme.typography.headlineMedium)
                    }
                }
                items(state.artists, key = { it.name }) { artist ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenArtist(artist.name) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        CoverImage(
                            model = app.repository.coverUrl(artist.coverAlbumId),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape),
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 14.dp),
                        ) {
                            Text(
                                artist.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    app: ShelfieApp,
    artistName: String,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    controller: androidx.media3.session.MediaController? = null,
) {
    val ui by produceState<ArtistsUiDetail>(initialValue = ArtistsUiDetail.Loading, artistName) {
        value = withContext(Dispatchers.IO) {
            try {
                val albums = app.repository.podcasts().filter {
                    it.media.metadata.displayAuthor?.trim().orEmpty().ifBlank { "Unknown Artist" } == artistName
                }
                ArtistsUiDetail.Ready(albums)
            } catch (e: Exception) {
                ArtistsUiDetail.Error(e.message ?: "Failed to load artist")
            }
        }
    }

    when (val state = ui) {
        is ArtistsUiDetail.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is ArtistsUiDetail.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }

        is ArtistsUiDetail.Ready -> {
            val scope = rememberCoroutineScope()
            val pins by app.pins.pins.collectAsState()
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                        Text(artistName, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (state.albums.size == 1) "1 album" else "${state.albums.size} albums",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.albums.chunked(2), key = { it.first().id }) { pair ->
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
                                actions = albumMenuActions(app, scope, controller, pins, album),
                            )
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private sealed interface ArtistsUiDetail {
    data object Loading : ArtistsUiDetail
    data class Error(val message: String) : ArtistsUiDetail
    data class Ready(val albums: List<LibraryItemSummary>) : ArtistsUiDetail
}

/** A square album card used in two-column grids (artist page, Library recently added). */
@Composable
fun AlbumGridCard(
    album: LibraryItemSummary,
    coverUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: AlbumMenuActions? = null,
) {
    val content: @Composable () -> Unit = {
        Column {
            CoverImage(
                model = coverUrl,
                contentDescription = album.media.metadata.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Text(
                album.media.metadata.title ?: "Album",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
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
    if (actions != null) {
        AlbumLongPressBox(onClick = onClick, actions = actions, modifier = modifier) { content() }
    } else {
        Box(modifier.clickable(onClick = onClick)) { content() }
    }
}
