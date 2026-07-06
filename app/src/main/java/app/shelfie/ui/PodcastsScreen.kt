package app.shelfie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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

private const val ALBUMS_PAGE_SIZE = 26

/**
 * The Albums grid, paged 26 at a time: fetching the whole album library in
 * one request times out on large servers.
 */
@Composable
fun PodcastsScreen(
    app: ShelfieApp,
    onOpenPodcast: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    controller: androidx.media3.session.MediaController? = null,
) {
    var albums by remember { mutableStateOf<List<LibraryItemSummary>>(emptyList()) }
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
                app.repository.albumsPage(startIndex, ALBUMS_PAGE_SIZE)
            }
        }
        result.fold(
            onSuccess = { page ->
                if (startIndex == 0) {
                    total = page.total
                    if (albums.take(page.albums.size) != page.albums) albums = page.albums
                } else {
                    albums = (albums + page.albums).distinctBy { it.id }
                    total = page.total
                }
                error = null
            },
            onFailure = { e ->
                if (albums.isEmpty()) error = e.message ?: "Failed to load albums"
            },
        )
    }

    LaunchedEffect(refreshKey) {
        if (albums.isEmpty()) {
            val cached = withContext(Dispatchers.IO) {
                runCatching { app.repository.cachedAlbumsFirstPage() }.getOrDefault(emptyList())
            }
            if (cached.isNotEmpty()) {
                albums = cached
                total = maxOf(
                    albums.size,
                    withContext(Dispatchers.IO) {
                        runCatching { app.repository.cachedAlbumsTotal() }.getOrDefault(0)
                    },
                )
                initialLoading = false
            }
        }
        if (refreshKey == 0 && albums.isNotEmpty() && !claimSessionRefresh("albums")) {
            initialLoading = false
            return@LaunchedEffect
        }
        refreshing = true
        loadPage(0)
        refreshing = false
        initialLoading = false
    }

    // Pagination lives in one long-lived collector: keying a LaunchedEffect on
    // scroll state cancels an in-flight page load mid-request (leaving the
    // spinner stuck), because adding the spinner row itself changes the keys.
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (lastVisible, count) ->
            val nearEnd = lastVisible >= count - 6
            if (nearEnd && !initialLoading && !loadingMore && albums.isNotEmpty() && albums.size < total) {
                loadingMore = true
                try {
                    loadPage(albums.size)
                } finally {
                    loadingMore = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "Albums",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (total > 0) {
                Text(
                    if (albums.size < total) "${albums.size} of $total albums" else "${albums.size} albums",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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

                error != null && albums.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                }

                else -> {
                    val scope = rememberCoroutineScope()
                    val pins by app.pins.pins.collectAsState()
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(albums, key = { it.id }) { podcast ->
                            PodcastCard(
                                podcast = podcast,
                                coverUrl = app.repository.coverUrl(podcast.id),
                                onClick = { onOpenPodcast(podcast.id) },
                                actions = albumMenuActions(app, scope, controller, pins, podcast),
                            )
                        }
                        if (loadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(4.dp),
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
}

@Composable
private fun PodcastCard(
    podcast: LibraryItemSummary,
    coverUrl: String,
    onClick: () -> Unit,
    actions: AlbumMenuActions,
) {
    AlbumLongPressBox(onClick = onClick, actions = actions) {
        Column {
            CoverImage(
                model = coverUrl,
                contentDescription = podcast.media.metadata.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Text(
                text = podcast.media.metadata.title ?: "Album",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (podcast.media.numEpisodes > 0) {
                Text(
                    text = "${podcast.media.numEpisodes} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
