package app.shelfie.pin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * An item pinned to the top of the Library tab, Apple Music-style.
 * [kind] is "song", "album", "artist", or "playlist"; [id] is the album id
 * for songs/albums, the artist name, or the playlist id. [songId] is the
 * track id when kind == "song".
 */
@Serializable
data class PinnedItem(
    val kind: String,
    val id: String,
    val songId: String = "",
    val title: String,
    val subtitle: String = "",
)

/** User-pinned library items, persisted to app storage. */
class PinStore(context: Context) {

    private val file = File(context.filesDir, "pins.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _pins = MutableStateFlow(load())
    val pins: StateFlow<List<PinnedItem>> = _pins.asStateFlow()

    fun isPinned(kind: String, id: String, songId: String = ""): Boolean =
        _pins.value.any { it.matches(kind, id, songId) }

    /** Pins the item, or unpins it if already pinned. */
    fun toggle(item: PinnedItem) {
        val current = _pins.value
        update(
            if (current.any { it.matches(item.kind, item.id, item.songId) }) {
                current.filterNot { it.matches(item.kind, item.id, item.songId) }
            } else {
                current + item
            },
        )
    }

    fun remove(item: PinnedItem) {
        update(_pins.value.filterNot { it.matches(item.kind, item.id, item.songId) })
    }

    @Synchronized
    private fun update(pins: List<PinnedItem>) {
        _pins.value = pins
        runCatching { file.writeText(json.encodeToString(pins)) }
    }

    private fun load(): List<PinnedItem> = runCatching {
        if (file.exists()) json.decodeFromString<List<PinnedItem>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun PinnedItem.matches(kind: String, id: String, songId: String): Boolean =
        this.kind == kind && this.id == id && this.songId == songId
}
