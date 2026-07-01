package app.shelfie.ui

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import app.shelfie.data.PodcastEpisode

fun MediaController.playEpisode(itemId: String, episodeId: String) {
    setMediaItem(MediaItem.Builder().setMediaId("episode:$itemId:$episodeId").build())
    prepare()
    play()
}

fun MediaController.playTrack(itemId: String, trackIndex: Int) {
    setMediaItem(MediaItem.Builder().setMediaId("track:$itemId:$trackIndex").build())
    prepare()
    play()
}

/** Inserts a song right after the current one; starts playback if idle. */
fun MediaController.playNext(itemId: String, episodeId: String) {
    val item = MediaItem.Builder().setMediaId(episodeMediaId(itemId, episodeId)).build()
    if (mediaItemCount == 0) {
        setMediaItem(item)
        prepare()
        play()
    } else {
        addMediaItem(currentMediaItemIndex + 1, item)
    }
}

/**
 * Plays a list of songs starting at [startIndex]. The queue is capped so a
 * multi-hundred-song library doesn't resolve hundreds of items at once.
 */
fun MediaController.playSongs(songs: List<PodcastEpisode>, startIndex: Int = 0, maxQueue: Int = 50) {
    if (songs.isEmpty()) return
    val slice = songs.drop(startIndex.coerceIn(0, songs.size - 1)).take(maxQueue)
    setMediaItems(
        slice.map { MediaItem.Builder().setMediaId(episodeMediaId(it.libraryItemId, it.id)).build() },
    )
    prepare()
    play()
}

fun episodeMediaId(itemId: String, episodeId: String): String = "episode:$itemId:$episodeId"

fun trackMediaId(itemId: String, trackIndex: Int): String = "track:$itemId:$trackIndex"
