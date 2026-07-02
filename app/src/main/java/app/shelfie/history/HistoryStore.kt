package app.shelfie.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PlayedSong(
    val itemId: String,
    val songId: String,
    val title: String,
    val artist: String = "",
    val playedAt: Long = 0,
)

/**
 * All-time play history, newest first, capped at the last 50 songs and
 * persisted to app storage. Replays move a song back to the top rather than
 * duplicating it.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _history = MutableStateFlow(load())
    val history: StateFlow<List<PlayedSong>> = _history.asStateFlow()

    fun record(itemId: String, songId: String, title: String, artist: String) {
        if (songId.isBlank()) return
        val rest = _history.value.filterNot { it.itemId == itemId && it.songId == songId }
        val entry = PlayedSong(
            itemId = itemId,
            songId = songId,
            title = title,
            artist = artist,
            playedAt = System.currentTimeMillis(),
        )
        update((listOf(entry) + rest).take(MAX_ENTRIES))
    }

    @Synchronized
    private fun update(entries: List<PlayedSong>) {
        _history.value = entries
        runCatching { file.writeText(json.encodeToString(entries)) }
    }

    private fun load(): List<PlayedSong> = runCatching {
        if (file.exists()) json.decodeFromString<List<PlayedSong>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
