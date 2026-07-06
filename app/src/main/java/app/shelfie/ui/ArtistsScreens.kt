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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shelfie.ShelfieApp
import app.shelfie.data.AbsRepository
import app.shelfie.data.LibraryItemSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ARTISTS_PAGE_SIZE = 26

/**
 * The Artists list, paged 26 at a time via Jellyfin's album-artists
 * endpoint (deriving artists from the full album library times out on
 * large servers).
 */
@Composable
fun ArtistsScreen(
    app: ShelfieApp,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    var artists by remember { mutableStateOf<List<AbsRepository.JellyArtist>>(emptyList()) }
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
                app.repository.artistsPage(startIndex, ARTISTS_PAGE_SIZE)
            }
        }
        result.fold(
            onSuccess = { page ->
                if (startIndex == 0) {
                    total = page.total
                    if (artists.take(page.artists.size) != page.artists) artists = page.artists
                } else {
                    artists = (artists + page.artists).distinctBy { it.id }
                    total = page.total
                }
                error = null
            },
            onFailure = { e ->
                if (artists.isEmpty()) error = e.message ?: "Failed to load artists"
            },
        )
    }

    LaunchedEffect(refreshKey) {
        if (artists.isEmpty()) {
            val cached = withContext(Dispatchers.IO) {
                runCatching { app.repository.cachedArtistsFirstPage() }.getOrDefault(emptyList())
            }
            if (cached.isNotEmpty()) {
                artists = cached
                total = maxOf(
                    cached.size,
                    withContext(Dispatchers.IO) {
                        runCatching { app.repository.cachedArtistsTotal() }.getOrDefault(0)
                    },
                )
                initialLoading = false
            }
        }
        if (refreshKey == 0 && artists.isNotEmpty() && !claimSessionRefresh("artists")) {
            initialLoading = false
            return@LaunchedEffect
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
    LaunchedEffect(nearEnd, artists.size) {
        if (nearEnd && !initialLoading && !loadingMore && artists.isNotEmpty() && artists.size < total) {
            loadingMore = true
            loadPage(artists.size)
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

            error != null && artists.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }

            else -> {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                            Text("Artists", style = MaterialTheme.typography.headlineMedium)
                            if (total > 0) {
                                Text(
                                    if (artists.size < total) "${artists.size} of $total artists" else "${artists.size} artists",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(artists, key = { it.id }) { artist ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenArtist(artist.name) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            CoverImage(
                                model = app.repository.coverUrl(artist.id),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape),
                            )
                            Text(
                                artist.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                    if (loadingMore) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
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

@Composable
fun ArtistDetailScreen(
    app: ShelfieApp,
    artistName: String,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    controller: androidx.media3.session.MediaController? = null,
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val albumsState = rememberServerData(
        key = artistName,
        refreshKey = refreshKey,
        sessionKey = "artist:$artistName",
        cached = { null },
        fetch = {
            if (!app.repository.ensureConfigured()) throw IllegalStateException("Not logged in")
            app.repository.artistAlbums(artistName)
        },
    )
    val ui = when {
        albumsState.data != null -> ArtistsUiDetail.Ready(albumsState.data.orEmpty())
        albumsState.error != null -> ArtistsUiDetail.Error(albumsState.error.orEmpty())
        else -> ArtistsUiDetail.Loading
    }

    RefreshablePage(
        refreshing = albumsState.refreshing,
        onRefresh = { refreshKey++ },
    ) {
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
                itemsIndexed(state.albums.chunked(2)) { index, pair ->
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
