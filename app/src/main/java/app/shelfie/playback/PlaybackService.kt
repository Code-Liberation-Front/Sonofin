package app.shelfie.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.shelfie.ShelfieApp
import app.shelfie.data.BookTrack
import app.shelfie.data.LibraryItemExpanded
import app.shelfie.data.LibraryItemSummary
import app.shelfie.data.PodcastEpisode
import app.shelfie.pin.PinnedItem
import app.shelfie.ui.MainActivity
import app.shelfie.ui.artistsFromAlbums
import app.shelfie.ui.sortedByAlbumOrder
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROOT_ID = "root"
private const val CONTINUE_ID = "continue"
private const val PODCASTS_ID = "podcasts"
private const val PLAYLISTS_ID = "playlistsRoot"
private const val ARTISTS_ID = "artistsRoot"
private const val TOPPICKS_ID = "topPicks"
private const val SONGS_ID = "allSongs"
private const val PODCAST_PREFIX = "podcast:"
private const val EPISODE_PREFIX = "episode:"
private const val TRACK_PREFIX = "track:"
private const val ARTIST_PREFIX = "artistName:"
private const val PLAYLIST_PREFIX = "userPlaylist:"
private const val MIX_PREFIX = "mixList:"
// Songs browsed from a mix/playlist carry their collection so tapping one
// queues the rest of that collection (not the song's album).
private const val MIXSONG_PREFIX = "mixSong:"
private const val PLSONG_PREFIX = "plSong:"
private const val DOWNLOADED_PLAYLIST_ID = "__downloaded__"

// Extras carried on audiobook track items for progress reporting.
private const val EXTRA_TRACK_START_OFFSET = "app.shelfie.trackStartOffset"
private const val EXTRA_BOOK_DURATION = "app.shelfie.bookDuration"

// Android Auto content-style hints (androidx.media legacy extras understood by Auto).
private const val EXTRA_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val EXTRA_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val STYLE_LIST = 1
private const val STYLE_GRID = 2

// Android Auto completion badges on playable items.
private const val EXTRA_COMPLETION_STATUS = "android.media.extra.PLAYBACK_STATUS"
private const val EXTRA_COMPLETION_PERCENTAGE = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"

// Episode publish date, carried in the media metadata so the player UI can show
// it under the podcast name.
internal const val EXTRA_PUBLISHED_AT = "app.shelfie.publishedAt"
internal const val EXTRA_PUB_DATE = "app.shelfie.pubDate"
private const val STATUS_NOT_PLAYED = 0
private const val STATUS_PARTIALLY_PLAYED = 1
private const val STATUS_FULLY_PLAYED = 2

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val activePlayer: Player? get() = mediaSession?.player

    private val app get() = application as ShelfieApp
    private val repo get() = app.repository

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // MUSIC content ducks the volume during notifications and
                    // other transient focus losses instead of pausing (speech
                    // content pauses, a leftover from the podcast era).
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // No custom layout: the notification and Auto show the default
        // previous / play-pause / next transport controls.
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    recordHistory()
                } else {
                    serviceScope.launch { pushProgress() }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                maybeExtendQueue()
                if (player.isPlaying) recordHistory()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) maybeExtendQueue()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                // The effect is bound to the audio session; re-attach on changes.
                serviceScope.launch {
                    applyNormalization(app.settings.normalizeAudioEnabled())
                }
            }
        })
        serviceScope.launch {
            app.settings.normalizeAudio.collect { enabled -> applyNormalization(enabled) }
        }
        initCast()
        startProgressSync()
    }

    /**
     * Peak normalization: a limiter (via DynamicsProcessing, Android 9+) on the
     * player's audio session that clamps volume spikes such as loud ad breaks.
     */
    private fun applyNormalization(enabled: Boolean) {
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        if (!enabled || Build.VERSION.SDK_INT < 28) return
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                /* channelCount= */ 2,
                /* preEqInUse= */ false, /* preEqBandCount= */ 0,
                /* mbcInUse= */ false, /* mbcBandCount= */ 0,
                /* postEqInUse= */ false, /* postEqBandCount= */ 0,
                /* limiterInUse= */ true,
            ).build()
            dynamicsProcessing = DynamicsProcessing(0, sessionId, config).apply {
                val limiter = DynamicsProcessing.Limiter(
                    /* inUse= */ true,
                    /* enabled= */ true,
                    /* linkGroup= */ 0,
                    /* attackTime= */ 1f,
                    /* releaseTime= */ 60f,
                    /* ratio= */ 8f,
                    /* threshold= */ -14f,
                    /* postGain= */ 3f,
                )
                setLimiterAllChannelsTo(limiter)
                setEnabled(true)
            }
        }
    }

    /** Chromecast support: swaps the session's player when a cast session starts/ends. */
    private fun initCast() {
        runCatching {
            val castContext = CastContext.getSharedInstance(this)
            castPlayer = CastPlayer(castContext, DefaultMediaItemConverter(), 10_000, 30_000).apply {
                setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = switchPlayer(this@apply)
                    override fun onCastSessionUnavailable() = switchPlayer(player)
                })
            }
        }
    }

    private fun switchPlayer(newPlayer: Player) {
        val session = mediaSession ?: return
        val old = session.player
        if (old === newPlayer) return
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playWhenReady = old.playWhenReady
        session.player = newPlayer
        if (items.isNotEmpty()) {
            newPlayer.setMediaItems(items, index, position)
            newPlayer.playWhenReady = playWhenReady
            newPlayer.prepare()
        }
        old.stop()
        old.clearMediaItems()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        mediaSession?.release()
        mediaSession = null
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        player.release()
        super.onDestroy()
    }

    /** Adds the playing song to the persistent all-time history. */
    private fun recordHistory() {
        val item = activePlayer?.currentMediaItem ?: return
        val mediaId = item.mediaId
        if (!mediaId.startsWith(EPISODE_PREFIX)) return
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return
        app.history.record(
            itemId = parts[1],
            songId = parts[2],
            title = item.mediaMetadata.title?.toString() ?: "Song",
            artist = item.mediaMetadata.artist?.toString() ?: "",
        )
    }

    // region autoplay continuation

    private var extendingQueue = false

    /**
     * Random continuation: when the autoplay setting is on and the queue is on
     * its last song, appends a batch of random library songs so playback keeps
     * going after the album/playlist/mix finishes. Audiobook tracks are left
     * alone, and the appended items are fully resolved (with URIs) since they
     * bypass the session's item-resolution callbacks.
     */
    private fun maybeExtendQueue() {
        val current = activePlayer ?: return
        if (current.mediaItemCount == 0) return
        if (current.currentMediaItemIndex < current.mediaItemCount - 1) return
        val currentId = current.currentMediaItem?.mediaId ?: return
        if (!currentId.startsWith(EPISODE_PREFIX)) return
        if (extendingQueue) return
        extendingQueue = true
        serviceScope.launch {
            try {
                if (!runCatching { app.settings.autoPlayEnabled() }.getOrDefault(true)) return@launch
                val existing = (0 until current.mediaItemCount)
                    .map { current.getMediaItemAt(it).mediaId }
                    .toSet()
                val items = withContext(Dispatchers.IO) {
                    runCatching {
                        if (!repo.ensureConfigured()) return@runCatching emptyList<MediaItem>()
                        repo.randomSongs(25)
                            .filter { it.libraryItemId.isNotBlank() && it.id.isNotBlank() }
                            .filter { "$EPISODE_PREFIX${it.libraryItemId}:${it.id}" !in existing }
                            .take(10)
                            .map { songItem(it, withUri = true) }
                    }.getOrDefault(emptyList())
                }
                if (items.isEmpty()) return@launch
                val wasEnded = current.playbackState == Player.STATE_ENDED
                val resumeIndex = current.mediaItemCount
                current.addMediaItems(items)
                // If the queue already finished while we were fetching, kick
                // playback into the first appended song.
                if (wasEnded) {
                    current.seekTo(resumeIndex, 0)
                    current.play()
                }
            } finally {
                extendingQueue = false
            }
        }
    }

    // endregion

    // region progress sync

    private fun startProgressSync() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                if (activePlayer?.isPlaying == true) pushProgress()
            }
        }
    }

    /** Reads player state on the main thread, then reports progress to the server. */
    private suspend fun pushProgress() {
        val current = activePlayer ?: return
        val mediaId = current.currentMediaItem?.mediaId ?: return
        if (current.playbackState != Player.STATE_READY && current.playbackState != Player.STATE_ENDED) return
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return
        val positionMs = current.currentPosition
        app.settings.saveLastPlayed(mediaId, positionMs)
        when {
            mediaId.startsWith(EPISODE_PREFIX) -> {
                val durationMs = current.duration
                val durationSec = if (durationMs != C.TIME_UNSET) durationMs / 1000.0 else 0.0
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (repo.ensureConfigured()) {
                            repo.updateProgress(parts[1], parts[2], positionMs / 1000.0, durationSec)
                        }
                    }
                }
            }

            mediaId.startsWith(TRACK_PREFIX) -> {
                // Book progress is reported against the whole book timeline.
                val extras = current.currentMediaItem?.mediaMetadata?.extras
                val startOffset = extras?.getDouble(EXTRA_TRACK_START_OFFSET) ?: 0.0
                val bookDuration = extras?.getDouble(EXTRA_BOOK_DURATION) ?: 0.0
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (repo.ensureConfigured()) {
                            repo.updateProgress(parts[1], "", startOffset + positionMs / 1000.0, bookDuration)
                        }
                    }
                }
            }
        }
    }

    // endregion

    // region browse tree / search / item resolution

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Sonofin")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            val rootExtras = Bundle().apply {
                putInt(EXTRA_STYLE_BROWSABLE, STYLE_GRID)
                putInt(EXTRA_STYLE_PLAYABLE, STYLE_LIST)
            }
            val rootParams = MediaLibraryService.LibraryParams.Builder().setExtras(rootExtras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                try {
                    if (!repo.ensureConfigured()) {
                        return@future LibraryResult.ofError<ImmutableList<MediaItem>>(
                            LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
                        )
                    }
                    val children: List<MediaItem> = withContext(Dispatchers.IO) {
                        when {
                            parentId == ROOT_ID -> rootTabs()

                            // Pinned songs first, then recently played — like the app.
                            parentId == CONTINUE_ID -> {
                                val pinned = app.pins.pins.value
                                    .filter { it.kind == "song" && it.songId.isNotBlank() }
                                    .map { pinnedSongItem(it) }
                                pinned + repo.continueListening().map {
                                    episodeItem(it.podcast, it.episode, withUri = false)
                                }
                            }

                            parentId == PLAYLISTS_ID -> playlistFolders()

                            parentId == ARTISTS_ID ->
                                artistsFromAlbums(repo.podcasts()).map { artist ->
                                    folderItem(
                                        id = "$ARTIST_PREFIX${artist.name}",
                                        title = artist.name,
                                        subtitle = if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums",
                                        extras = Bundle().apply { putInt(EXTRA_STYLE_BROWSABLE, STYLE_GRID) },
                                        artworkUri = Uri.parse(repo.coverUrl(artist.coverAlbumId)),
                                    )
                                }

                            parentId.startsWith(ARTIST_PREFIX) -> {
                                val name = parentId.removePrefix(ARTIST_PREFIX)
                                repo.podcasts()
                                    .filter {
                                        it.media.metadata.displayAuthor?.trim().orEmpty()
                                            .ifBlank { "Unknown Artist" } == name
                                    }
                                    .map { it.toBrowsableItem() }
                            }

                            parentId == TOPPICKS_ID ->
                                repo.topPicks().map { it.toBrowsableItem() }

                            parentId == SONGS_ID ->
                                repo.songsPage(0, 100).songs.map { songItem(it) }

                            parentId.startsWith(MIX_PREFIX) -> {
                                val mixId = parentId.removePrefix(MIX_PREFIX)
                                repo.mix(mixId)?.songs.orEmpty().map { song ->
                                    songItem(song, mediaId = "$MIXSONG_PREFIX$mixId:${song.libraryItemId}:${song.id}")
                                }
                            }

                            parentId.startsWith(PLAYLIST_PREFIX) -> {
                                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX)
                                if (playlistId == DOWNLOADED_PLAYLIST_ID) {
                                    app.downloads.completed.value.map { entry ->
                                        collectionSongItem(
                                            mediaId = "$PLSONG_PREFIX$playlistId:${entry.itemId}:${entry.episodeId}",
                                            title = entry.title,
                                            subtitle = entry.podcastTitle,
                                            coverItemId = entry.itemId,
                                        )
                                    }
                                } else {
                                    app.playlist.playlists.value
                                        .firstOrNull { it.id == playlistId }
                                        ?.entries.orEmpty()
                                        .map { entry ->
                                            collectionSongItem(
                                                mediaId = "$PLSONG_PREFIX$playlistId:${entry.itemId}:${entry.episodeId}",
                                                title = entry.title,
                                                subtitle = entry.podcastTitle,
                                                coverItemId = entry.itemId,
                                            )
                                        }
                                }
                            }

                            // Top Picks shelf leads the Albums grid, like Home.
                            parentId == PODCASTS_ID -> {
                                val topPicks = runCatching { repo.topPicks() }.getOrDefault(emptyList())
                                val shelf = if (topPicks.isEmpty()) {
                                    emptyList()
                                } else {
                                    listOf(
                                        folderItem(
                                            id = TOPPICKS_ID,
                                            title = "Top Picks for You",
                                            extras = Bundle().apply { putInt(EXTRA_STYLE_BROWSABLE, STYLE_GRID) },
                                            artworkUri = Uri.parse(repo.coverUrl(topPicks.first().id)),
                                        ),
                                    )
                                }
                                shelf + repo.podcasts().map { it.toBrowsableItem() }
                            }

                            parentId.startsWith(PODCAST_PREFIX) -> {
                                val itemId = parentId.removePrefix(PODCAST_PREFIX)
                                val podcast = repo.podcast(itemId)
                                podcast.media.episodes
                                    .sortedByAlbumOrder()
                                    .map { episodeItem(podcast, it, withUri = false) }
                            }

                            else -> emptyList()
                        }
                    }
                    LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            serviceScope.future {
                try {
                    val item = withContext(Dispatchers.IO) { resolveAny(mediaId) }
                    if (item != null) {
                        LibraryResult.ofItem(item, null)
                    } else {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    }
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            serviceScope.future {
                try {
                    val results = withContext(Dispatchers.IO) { searchItems(query) }
                    session.notifySearchResultChanged(browser, query, results.size, params)
                    LibraryResult.ofVoid()
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                try {
                    val results = withContext(Dispatchers.IO) { searchItems(query) }
                    LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            serviceScope.future {
                withContext(Dispatchers.IO) {
                    mediaItems.mapNotNull { runCatching { resolveAny(it.mediaId) }.getOrNull() }
                        .toMutableList()
                }
            }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            serviceScope.future {
                withContext(Dispatchers.IO) {
                    // Voice queries ("play <podcast> on Shelfie") arrive as items with a
                    // search query instead of a media id.
                    val voiceQuery = mediaItems
                        .firstOrNull { !it.requestMetadata.searchQuery.isNullOrBlank() }
                        ?.requestMetadata?.searchQuery
                    val episodeIds = mediaItems.filter { it.mediaId.startsWith(EPISODE_PREFIX) }
                    val trackIds = mediaItems.filter { it.mediaId.startsWith(TRACK_PREFIX) }
                    val mixSong = mediaItems.firstOrNull { it.mediaId.startsWith(MIXSONG_PREFIX) }
                    val playlistSong = mediaItems.firstOrNull { it.mediaId.startsWith(PLSONG_PREFIX) }

                    when {
                        // Song tapped inside a mix: queue the rest of the mix.
                        mixSong != null -> mixQueueFor(mixSong.mediaId, startPositionMs)

                        // Song tapped inside a playlist: queue the rest of it.
                        playlistSong != null -> playlistQueueFor(playlistSong.mediaId, startPositionMs)

                        // Audiobook track tapped: queue the whole book at that track.
                        trackIds.isNotEmpty() ->
                            bookQueueFor(trackIds[0].mediaId, startPositionMs)

                        // Single episode tapped: queue the whole podcast so next/previous work.
                        episodeIds.size == 1 ->
                            podcastQueueFor(episodeIds[0].mediaId, startPositionMs)

                        episodeIds.isNotEmpty() -> {
                            val resolved = episodeIds.mapNotNull {
                                runCatching { resolveAny(it.mediaId) }.getOrNull()
                            }
                            val index = if (startIndex == C.INDEX_UNSET) 0
                            else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
                            var position = startPositionMs
                            if (position == C.TIME_UNSET && resolved.isNotEmpty()) {
                                position = savedPositionMs(resolved[index].mediaId)
                            }
                            MediaSession.MediaItemsWithStartPosition(resolved, index, position)
                        }

                        !voiceQuery.isNullOrBlank() -> queueForVoiceQuery(voiceQuery)

                        else -> MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                    }
                }
            }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            serviceScope.future {
                val (mediaId, storedPositionMs) = app.settings.lastPlayed()
                    ?: throw UnsupportedOperationException("Nothing to resume")
                withContext(Dispatchers.IO) {
                    if (mediaId.startsWith(TRACK_PREFIX)) {
                        bookQueueFor(mediaId, storedPositionMs)
                    } else {
                        val serverPositionMs = savedPositionMs(mediaId)
                        val position = if (serverPositionMs != C.TIME_UNSET) serverPositionMs else storedPositionMs
                        podcastQueueFor(mediaId, position)
                    }
                }
            }
    }

    // Android Auto shows at most four root tabs; everything else is nested.
    private fun rootTabs(): List<MediaItem> = listOf(
        folderItem(
            id = CONTINUE_ID,
            title = "Recently Played",
            extras = Bundle().apply { putInt(EXTRA_STYLE_PLAYABLE, STYLE_LIST) },
        ),
        folderItem(
            id = PLAYLISTS_ID,
            title = "Playlists",
            extras = Bundle().apply { putInt(EXTRA_STYLE_BROWSABLE, STYLE_LIST) },
        ),
        folderItem(
            id = ARTISTS_ID,
            title = "Artists",
            extras = Bundle().apply { putInt(EXTRA_STYLE_BROWSABLE, STYLE_LIST) },
        ),
        folderItem(
            id = PODCASTS_ID,
            title = "Albums",
            extras = Bundle().apply { putInt(EXTRA_STYLE_BROWSABLE, STYLE_GRID) },
        ),
    )

    private fun folderItem(
        id: String,
        title: String,
        extras: Bundle,
        artworkUri: Uri? = null,
        subtitle: String? = null,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setArtworkUri(artworkUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                    .setExtras(extras)
                    .build(),
            )
            .build()

    /** The Playlists tab: Downloaded, user playlists, Made for You mixes, all songs. */
    private suspend fun playlistFolders(): List<MediaItem> {
        val listExtras = { Bundle().apply { putInt(EXTRA_STYLE_PLAYABLE, STYLE_LIST) } }
        val items = mutableListOf<MediaItem>()
        items += folderItem(
            id = "$PLAYLIST_PREFIX$DOWNLOADED_PLAYLIST_ID",
            title = "Downloaded",
            extras = listExtras(),
        )
        app.playlist.playlists.value.forEach { playlist ->
            items += folderItem(
                id = "$PLAYLIST_PREFIX${playlist.id}",
                title = playlist.name,
                subtitle = "${playlist.entries.size} songs",
                extras = listExtras(),
            )
        }
        runCatching { repo.madeForYou() }.getOrDefault(emptyList()).forEach { mix ->
            items += folderItem(
                id = "$MIX_PREFIX${mix.id}",
                title = mix.title,
                subtitle = mix.subtitle,
                extras = listExtras(),
                artworkUri = mix.songs.firstOrNull()
                    ?.libraryItemId?.takeIf { it.isNotBlank() }
                    ?.let { Uri.parse(repo.coverUrl(it)) },
            )
        }
        items += folderItem(id = SONGS_ID, title = "Songs", extras = listExtras())
        return items
    }

    /** Browse-search and voice-search results: matching podcasts first, then episodes. */
    private suspend fun searchItems(query: String): List<MediaItem> {
        if (!repo.ensureConfigured()) return emptyList()
        val (podcastMatches, episodeMatches) = repo.search(query)
        return podcastMatches.map { it.toBrowsableItem() } +
            episodeMatches.map { (podcast, episode) -> episodeItem(podcast, episode, withUri = false) }
    }

    /** Builds a queue for a voice query: best episode match, else latest episode of the best podcast. */
    private suspend fun queueForVoiceQuery(query: String): MediaSession.MediaItemsWithStartPosition {
        if (!repo.ensureConfigured()) return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val (podcastMatches, episodeMatches) = repo.search(query)
        val target: Pair<LibraryItemExpanded, PodcastEpisode>? = when {
            episodeMatches.isNotEmpty() -> episodeMatches.first()
            podcastMatches.isNotEmpty() -> {
                val podcast = repo.podcast(podcastMatches.first().id)
                // Voice-matched album: start from its first track.
                podcast.media.episodes.sortedByAlbumOrder().firstOrNull()?.let { podcast to it }
            }

            else -> null
        }
        val (podcast, episode) = target
            ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        return podcastQueueFor("$EPISODE_PREFIX${podcast.id}:${episode.id}", C.TIME_UNSET)
    }

    /**
     * Builds the queue for a selected song: the rest of its album in
     * disc/track order, starting at the tapped song — regardless of the
     * autoplay setting (autoplay only controls what happens after the album
     * ends; see [maybeExtendQueue]).
     */
    private suspend fun podcastQueueFor(
        mediaId: String,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3 || !repo.ensureConfigured()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
        val podcast = try {
            repo.podcast(parts[1])
        } catch (e: Exception) {
            // Offline with no cached metadata: fall back to the downloaded copy.
            val downloaded = downloadedEpisodeItem(parts[1], parts[2])
                ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
            var position = startPositionMs
            if (position == C.TIME_UNSET) {
                position = savedPositionMs(mediaId)
            }
            return MediaSession.MediaItemsWithStartPosition(listOf(downloaded), 0, position)
        }
        val ordered = podcast.media.episodes.sortedByAlbumOrder()
        val start = ordered.indexOfFirst { it.id == parts[2] }
        val episodes = if (start >= 0) ordered.drop(start) else ordered.filter { it.id == parts[2] }
        if (episodes.isEmpty()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
        val queue = episodes.map { episodeItem(podcast, it, withUri = true) }
        var position = startPositionMs
        if (position == C.TIME_UNSET) {
            position = savedPositionMs(mediaId)
        }
        return MediaSession.MediaItemsWithStartPosition(queue, 0, position)
    }

    /** Looks up the server-side resume position for an episode media id. */
    private suspend fun savedPositionMs(mediaId: String): Long {
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return C.TIME_UNSET
        val progress = runCatching { repo.progress(parts[1], parts[2]) }.getOrNull() ?: return C.TIME_UNSET
        if (progress.isFinished) return 0L
        return (progress.currentTime * 1000).toLong()
    }

    private suspend fun resolveAny(mediaId: String): MediaItem? = when {
        mediaId.startsWith(EPISODE_PREFIX) -> resolveEpisode(mediaId)
        mediaId.startsWith(TRACK_PREFIX) -> resolveTrack(mediaId)
        // Collection-scoped songs resolve to their underlying playable song.
        mediaId.startsWith(MIXSONG_PREFIX) ->
            parseCollectionSongId(mediaId, MIXSONG_PREFIX)?.let { (_, albumId, songId) ->
                resolveEpisode("$EPISODE_PREFIX$albumId:$songId")
            }

        mediaId.startsWith(PLSONG_PREFIX) ->
            parseCollectionSongId(mediaId, PLSONG_PREFIX)?.let { (_, albumId, songId) ->
                resolveEpisode("$EPISODE_PREFIX$albumId:$songId")
            }

        else -> null
    }

    /** Turns a "track:{itemId}:{index}" media id into a fully playable MediaItem. */
    private suspend fun resolveTrack(mediaId: String): MediaItem? {
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return null
        if (!repo.ensureConfigured()) return null
        val book = repo.podcast(parts[1])
        val index = parts[2].toIntOrNull() ?: return null
        val track = book.media.tracks.getOrNull(index) ?: return null
        return trackItem(book, index, track)
    }

    /**
     * Queues an entire audiobook positioned at the selected track, resuming inside
     * that track from the server-side book position when none was requested.
     */
    private suspend fun bookQueueFor(
        mediaId: String,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3 || !repo.ensureConfigured()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
        val book = repo.podcast(parts[1])
        val tracks = book.media.tracks
        if (tracks.isEmpty()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
        val startIndex = (parts[2].toIntOrNull() ?: 0).coerceIn(0, tracks.lastIndex)
        val queue = tracks.mapIndexed { index, track -> trackItem(book, index, track) }
        var position = startPositionMs
        if (position == C.TIME_UNSET) {
            val saved = runCatching { repo.bookProgress(parts[1]) }.getOrNull()
            val track = tracks[startIndex]
            val bookTime = saved?.currentTime ?: 0.0
            position = if (saved != null && !saved.isFinished &&
                bookTime >= track.startOffset && bookTime < track.startOffset + track.duration
            ) {
                ((bookTime - track.startOffset) * 1000).toLong()
            } else {
                0L
            }
        }
        return MediaSession.MediaItemsWithStartPosition(queue, startIndex, position)
    }

    private fun trackItem(book: LibraryItemExpanded, index: Int, track: BookTrack): MediaItem {
        val extras = Bundle().apply {
            putDouble(EXTRA_TRACK_START_OFFSET, track.startOffset)
            putDouble(EXTRA_BOOK_DURATION, book.media.duration)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title ?: "Part ${index + 1}")
            .setArtist(book.media.metadata.title)
            .setAlbumTitle(book.media.metadata.title)
            .setArtworkUri(Uri.parse(repo.coverUrl(book.id)))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setExtras(extras)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId("$TRACK_PREFIX${book.id}:$index")
            .setMediaMetadata(metadata)
        track.contentUrl?.let { contentUrl ->
            builder.setUri(repo.tokenizedUrl(contentUrl))
            builder.setMimeType("audio/mpeg")
        }
        return builder.build()
    }

    /**
     * Builds a playable item for a downloaded episode straight from the download
     * index, so offline playback never depends on the API cache (which can be
     * cleared by library switches or missing on some devices).
     */
    private fun downloadedEpisodeItem(itemId: String, episodeId: String): MediaItem? {
        val localUri = app.downloads.localUri(itemId, episodeId) ?: return null
        val entry = app.downloads.entry(itemId, episodeId) ?: return null
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.podcastTitle)
            .setAlbumTitle(entry.podcastTitle)
            .setArtworkUri(Uri.parse(repo.coverUrl(itemId)))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()
        return MediaItem.Builder()
            .setMediaId("$EPISODE_PREFIX$itemId:$episodeId")
            .setMediaMetadata(metadata)
            .setUri(localUri)
            .setMimeType("audio/mpeg")
            .build()
    }

    /** Turns an "episode:{itemId}:{episodeId}" media id into a fully playable MediaItem. */
    private suspend fun resolveEpisode(mediaId: String): MediaItem? {
        if (!mediaId.startsWith(EPISODE_PREFIX)) return null
        val parts = mediaId.split(":", limit = 3)
        if (parts.size != 3) return null
        return try {
            if (!repo.ensureConfigured()) return downloadedEpisodeItem(parts[1], parts[2])
            val podcast = repo.podcast(parts[1])
            val episode = podcast.media.episodes.firstOrNull { it.id == parts[2] }
                ?: return downloadedEpisodeItem(parts[1], parts[2])
            episodeItem(podcast, episode, withUri = true)
        } catch (e: Exception) {
            downloadedEpisodeItem(parts[1], parts[2]) ?: throw e
        }
    }

    private fun LibraryItemSummary.toBrowsableItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId("$PODCAST_PREFIX$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(media.metadata.title ?: "Album")
                    .setArtist(media.metadata.author)
                    .setArtworkUri(Uri.parse(repo.coverUrl(id)))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setExtras(Bundle().apply { putInt(EXTRA_STYLE_PLAYABLE, STYLE_LIST) })
                    .build(),
            )
            .build()

    /** A playable song row built from the song itself (mix, songs list, search). */
    private suspend fun songItem(
        episode: PodcastEpisode,
        mediaId: String = "$EPISODE_PREFIX${episode.libraryItemId}:${episode.id}",
        withUri: Boolean = false,
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title ?: "Song")
                    .setArtist(episode.subtitle)
                    .setArtworkUri(
                        episode.libraryItemId.takeIf { it.isNotBlank() }
                            ?.let { Uri.parse(repo.coverUrl(it)) },
                    )
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
        if (withUri) {
            val localUri = app.downloads.localUri(episode.libraryItemId, episode.id)
            if (localUri != null) {
                builder.setUri(localUri)
                builder.setMimeType(episode.audioFile?.mimeType ?: "audio/mpeg")
            } else {
                repo.streamUrl(episode.libraryItemId, episode)?.let { url ->
                    builder.setUri(url)
                    builder.setMimeType(episode.audioFile?.mimeType ?: "audio/mpeg")
                }
            }
        }
        return builder.build()
    }

    /** A playable row for a pinned song (title/artist come from the pin itself). */
    private fun pinnedSongItem(pin: PinnedItem): MediaItem =
        MediaItem.Builder()
            .setMediaId("$EPISODE_PREFIX${pin.id}:${pin.songId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(pin.title)
                    .setArtist(pin.subtitle.ifBlank { null })
                    .setArtworkUri(pin.id.takeIf { it.isNotBlank() }?.let { Uri.parse(repo.coverUrl(it)) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()

    /** A playable row inside a playlist, carrying the playlist in its media id. */
    private fun collectionSongItem(
        mediaId: String,
        title: String,
        subtitle: String,
        coverItemId: String,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle.ifBlank { null })
                    .setArtworkUri(coverItemId.takeIf { it.isNotBlank() }?.let { Uri.parse(repo.coverUrl(it)) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()

    /**
     * Parses "<prefix><collectionId>:<albumId>:<songId>" from the END, because
     * collection ids (e.g. "genre:Rock") may themselves contain colons while
     * Jellyfin item ids never do.
     */
    private fun parseCollectionSongId(mediaId: String, prefix: String): Triple<String, String, String>? {
        val rest = mediaId.removePrefix(prefix)
        val songId = rest.substringAfterLast(':', "")
        val head = rest.substringBeforeLast(':', "")
        val albumId = head.substringAfterLast(':', "")
        val collectionId = head.substringBeforeLast(':', "")
        if (songId.isBlank() || albumId.isBlank() || collectionId.isBlank()) return null
        return Triple(collectionId, albumId, songId)
    }

    /** Queues the rest of a mix starting from the tapped song. */
    private suspend fun mixQueueFor(
        mediaId: String,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        val (mixId, albumId, songId) = parseCollectionSongId(mediaId, MIXSONG_PREFIX)
            ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val mix = runCatching { repo.mix(mixId) }.getOrNull()
            ?: return podcastQueueFor("$EPISODE_PREFIX$albumId:$songId", startPositionMs)
        val index = mix.songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
        val queue = mix.songs.drop(index).take(50).map { songItem(it, withUri = true) }
        if (queue.isEmpty()) {
            return podcastQueueFor("$EPISODE_PREFIX$albumId:$songId", startPositionMs)
        }
        val position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        return MediaSession.MediaItemsWithStartPosition(queue, 0, position)
    }

    /** Queues the rest of a playlist (or the Downloaded list) from the tapped song. */
    private suspend fun playlistQueueFor(
        mediaId: String,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        val (playlistId, albumId, songId) = parseCollectionSongId(mediaId, PLSONG_PREFIX)
            ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val entries: List<Pair<String, String>> = if (playlistId == DOWNLOADED_PLAYLIST_ID) {
            app.downloads.completed.value.map { it.itemId to it.episodeId }
        } else {
            app.playlist.playlists.value.firstOrNull { it.id == playlistId }
                ?.entries.orEmpty().map { it.itemId to it.episodeId }
        }
        val index = entries.indexOfFirst { it.first == albumId && it.second == songId }.coerceAtLeast(0)
        val queue = entries.drop(index).take(50).mapNotNull { (item, ep) ->
            runCatching { resolveEpisode("$EPISODE_PREFIX$item:$ep") }.getOrNull()
        }
        if (queue.isEmpty()) {
            return podcastQueueFor("$EPISODE_PREFIX$albumId:$songId", startPositionMs)
        }
        val position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        return MediaSession.MediaItemsWithStartPosition(queue, 0, position)
    }

    private suspend fun episodeItem(
        podcast: LibraryItemExpanded,
        episode: PodcastEpisode,
        withUri: Boolean,
    ): MediaItem {
        val extras = Bundle()
        val progress = runCatching { repo.progress(podcast.id, episode.id) }.getOrNull()
        when {
            progress == null || progress.currentTime <= 0 ->
                extras.putInt(EXTRA_COMPLETION_STATUS, STATUS_NOT_PLAYED)

            progress.isFinished ->
                extras.putInt(EXTRA_COMPLETION_STATUS, STATUS_FULLY_PLAYED)

            else -> {
                extras.putInt(EXTRA_COMPLETION_STATUS, STATUS_PARTIALLY_PLAYED)
                extras.putDouble(EXTRA_COMPLETION_PERCENTAGE, progress.progress.coerceIn(0.0, 1.0))
            }
        }
        episode.publishedAt?.let { extras.putLong(EXTRA_PUBLISHED_AT, it) }
        episode.pubDate?.let { extras.putString(EXTRA_PUB_DATE, it) }
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title ?: "Song")
            .setArtist(
                episode.subtitle?.takeIf { it.isNotBlank() }
                    ?: podcast.media.metadata.displayAuthor,
            )
            .setAlbumTitle(podcast.media.metadata.title)
            .setArtworkUri(Uri.parse(repo.coverUrl(podcast.id)))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId("$EPISODE_PREFIX${podcast.id}:${episode.id}")
            .setMediaMetadata(metadata)
        if (withUri) {
            // Prefer the downloaded copy so playback works offline.
            val localUri = app.downloads.localUri(podcast.id, episode.id)
            if (localUri != null) {
                builder.setUri(localUri)
                builder.setMimeType(episode.audioFile?.mimeType ?: "audio/mpeg")
            } else {
                repo.streamUrl(podcast.id, episode)?.let { url ->
                    builder.setUri(url)
                    // The Cast media item converter requires a MIME type.
                    builder.setMimeType(episode.audioFile?.mimeType ?: "audio/mpeg")
                }
            }
        }
        return builder.build()
    }

    // endregion
}
