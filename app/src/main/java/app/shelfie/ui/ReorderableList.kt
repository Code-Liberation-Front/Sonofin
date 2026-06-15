package app.shelfie.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Minimal drag-to-reorder support for a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * [onMove] reorders the caller's backing list live as the dragged item passes
 * its neighbours; [onDrop] is called once when the gesture ends so the new
 * order can be persisted.
 */
class DragDropState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    private var initialOffset = 0
    private var delta by mutableFloatStateOf(0f)

    /** Vertical translation to apply to the item currently being dragged. */
    val draggingItemOffset: Float
        get() = currentInfo?.let { initialOffset + delta - it.offset } ?: 0f

    private val currentInfo: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    fun onDragStart(index: Int) {
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.also {
                draggingItemIndex = index
                initialOffset = it.offset
                delta = 0f
            }
    }

    fun onDrag(amountY: Float) {
        delta += amountY
        val info = currentInfo ?: return
        val from = draggingItemIndex ?: return
        val start = info.offset + draggingItemOffset
        val end = start + info.size
        val middle = start + info.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index != from && middle.toInt() in item.offset..(item.offset + item.size)
        }
        if (target != null) {
            onMove(from, target.index)
            draggingItemIndex = target.index
        } else {
            // Nudge-scroll when dragged past the visible edges.
            val viewStart = listState.layoutInfo.viewportStartOffset.toFloat()
            val viewEnd = listState.layoutInfo.viewportEndOffset.toFloat()
            val scrollAmount = when {
                amountY > 0 && end > viewEnd -> end - viewEnd
                amountY < 0 && start < viewStart -> start - viewStart
                else -> 0f
            }
            if (scrollAmount != 0f) scope.launch { listState.scrollBy(scrollAmount) }
        }
    }

    fun onDragEnd() {
        if (draggingItemIndex != null) onDrop()
        draggingItemIndex = null
        delta = 0f
    }
}

@Composable
fun rememberDragDropState(
    listState: LazyListState,
    onMove: (Int, Int) -> Unit,
    onDrop: () -> Unit,
): DragDropState {
    val scope = rememberCoroutineScope()
    val latestMove = rememberUpdatedState(onMove)
    val latestDrop = rememberUpdatedState(onDrop)
    return remember(listState) {
        DragDropState(
            listState = listState,
            scope = scope,
            onMove = { from, to -> latestMove.value(from, to) },
            onDrop = { latestDrop.value() },
        )
    }
}

/**
 * A drag handle that starts a reorder gesture for the row at [index]. Keyed on
 * the stable [key] so an active drag isn't cancelled when the row's index shifts.
 */
@Composable
fun DragHandle(
    state: DragDropState,
    key: Any,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val currentIndex = rememberUpdatedState(index)
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = "Drag to reorder",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.pointerInput(state, key) {
            detectDragGestures(
                onDragStart = { state.onDragStart(currentIndex.value) },
                onDrag = { change, amount ->
                    change.consume()
                    state.onDrag(amount.y)
                },
                onDragEnd = { state.onDragEnd() },
                onDragCancel = { state.onDragEnd() },
            )
        },
    )
}
