package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private sealed interface PodcastsUi {
    data object Loading : PodcastsUi
    data class Error(val message: String) : PodcastsUi
    data class Ready(val podcasts: List<LibraryItemSummary>) : PodcastsUi
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastsScreen(
    app: ShelfieApp,
    onOpenPodcast: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    controller: androidx.media3.session.MediaController? = null,
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    val ui by produceState<PodcastsUi>(initialValue = PodcastsUi.Loading, refreshKey) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.repository.ensureConfigured()) {
                    PodcastsUi.Error("Not logged in")
                } else {
                    PodcastsUi.Ready(app.repository.podcasts(forceRefresh = refreshKey > 0))
                }
            } catch (e: Exception) {
                PodcastsUi.Error(e.message ?: "Failed to load albums")
            }
        }
        isRefreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                refreshKey++
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            PodcastsContent(app, controller, onOpenPodcast, ui, onRetry = { refreshKey++ })
        }
    }
}

@Composable
private fun PodcastsContent(
    app: ShelfieApp,
    controller: androidx.media3.session.MediaController?,
    onOpenPodcast: (String) -> Unit,
    ui: PodcastsUi,
    onRetry: () -> Unit,
) {
    when (val state = ui) {
        is PodcastsUi.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is PodcastsUi.Error -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Retry")
                }
            }
        }

        is PodcastsUi.Ready -> {
            val scope = rememberCoroutineScope()
            val pins by app.pins.pins.collectAsState()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.podcasts, key = { it.id }) { podcast ->
                    PodcastCard(
                        podcast = podcast,
                        coverUrl = app.repository.coverUrl(podcast.id),
                        onClick = { onOpenPodcast(podcast.id) },
                        actions = albumMenuActions(app, scope, controller, pins, podcast),
                    )
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
