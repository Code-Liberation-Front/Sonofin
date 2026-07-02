package app.shelfie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Screen data loaded stale-while-revalidate: [data] paints instantly from the
 * persistent cache, then a background fetch compares against the server and
 * replaces it only when something actually changed. [refreshing] is true
 * while the background fetch runs; [error] is set only when there is nothing
 * cached to show.
 */
@Stable
class ServerDataState<T : Any> {
    var data by mutableStateOf<T?>(null)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * Screens that revalidate only once per app session (until a manual pull):
 * ids that already refreshed since process start.
 */
private val sessionRefreshed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

/**
 * Marks [sessionKey] as refreshed for this app session; returns true when
 * this call did the marking (i.e. the key still needed its session refresh).
 * For screens with bespoke loading (e.g. paged lists) that can't use
 * [rememberServerData].
 */
fun claimSessionRefresh(sessionKey: String): Boolean = sessionRefreshed.add(sessionKey)

/**
 * Loads screen data cache-first, then revalidates against the server.
 *
 * [key] is the identity of the data (e.g. an item id) — changing it resets
 * the state. [refreshKey] is the manual pull-to-refresh counter; [refetchKey]
 * triggers a re-fetch without resetting (e.g. a progress revision). When
 * [sessionKey] is set, the automatic revalidation runs only once per app
 * session — after that the screen paints purely from cache until the user
 * manually refreshes. [cached] must be fast and never touch the network;
 * [fetch] is the authoritative server call.
 */
@Composable
fun <T : Any> rememberServerData(
    key: Any? = Unit,
    refreshKey: Int = 0,
    refetchKey: Any? = Unit,
    sessionKey: String? = null,
    cached: suspend () -> T?,
    fetch: suspend () -> T,
): ServerDataState<T> {
    val state = remember(key) { ServerDataState<T>() }
    LaunchedEffect(key, refreshKey, refetchKey) {
        if (state.data == null) {
            withContext(Dispatchers.IO) { runCatching { cached() }.getOrNull() }
                ?.let { state.data = it }
        }
        // Session-scoped screens skip the automatic revalidation when they have
        // cached content and already refreshed once this session.
        if (sessionKey != null && refreshKey == 0 && state.data != null &&
            !sessionRefreshed.add(sessionKey)
        ) {
            state.refreshing = false
            return@LaunchedEffect
        }
        state.refreshing = true
        state.error = null
        val result = withContext(Dispatchers.IO) { runCatching { fetch() } }
        result.fold(
            onSuccess = { fresh -> if (fresh != state.data) state.data = fresh },
            onFailure = { e -> if (state.data == null) state.error = e.message ?: "Failed to load" },
        )
        state.refreshing = false
    }
    return state
}

/**
 * Standard page wrapper: swipe down anywhere to refresh, and a thin progress
 * bar pinned to the top whenever a background refresh is running. The pull
 * spinner itself only shows for user-initiated refreshes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshablePage(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var pullActive by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (!refreshing) pullActive = false
    }
    PullToRefreshBox(
        isRefreshing = pullActive && refreshing,
        onRefresh = {
            pullActive = true
            onRefresh()
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
