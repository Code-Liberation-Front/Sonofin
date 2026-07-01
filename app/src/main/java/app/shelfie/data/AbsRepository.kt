package app.shelfie.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point to the Jellyfin server. Holds the configured Retrofit
 * client plus small in-memory caches shared by the UI and the playback service.
 *
 * The repository speaks Jellyfin but exposes the app's "podcast/episode"
 * vocabulary (album = podcast, track = episode) so the rest of the app is
 * unaware of the backend. See [Models] for the mapping.
 */
class AbsRepository(
    private val settings: SettingsStore,
    /** Directory for offline JSON caches; falls back to network-only when null. */
    private val cacheDir: File? = null,
) {

    init {
        cacheDir?.mkdirs()
    }

    @Volatile
    var serverUrl: String = ""
        private set

    @Volatile
    var token: String = ""
        private set

    @Volatile
    private var userId: String = ""

    @Volatile
    private var deviceId: String = ""

    @Volatile
    private var api: AbsApi? = null

    private val itemCache = ConcurrentHashMap<String, LibraryItemExpanded>()
    private val progressByTrack = ConcurrentHashMap<String, MediaProgress>()

    @Volatile
    private var albumsCache: List<LibraryItemSummary> = emptyList()

    @Volatile
    private var latestCache: List<PodcastEpisode> = emptyList()

    @Volatile
    private var songsCache: List<PodcastEpisode> = emptyList()

    @Volatile
    private var topPicksCache: List<LibraryItemSummary> = emptyList()

    @Volatile
    private var mixesCache: List<Mix> = emptyList()

    @Volatile
    private var librariesCache: List<Library> = emptyList()

    // Bumped whenever progress is changed from the UI (reset / mark played),
    // so screens can reload to reflect the change immediately.
    private val _progressRevision = MutableStateFlow(0)
    val progressRevision: StateFlow<Int> = _progressRevision.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private fun configure(serverUrl: String, token: String, userId: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.token = token
        this.userId = userId
        api = buildApi(this.serverUrl)
    }

    /** Restores the client from persisted credentials. Returns false if not logged in. */
    suspend fun ensureConfigured(): Boolean {
        if (api != null) return true
        val creds = settings.snapshot()
        if (!creds.isLoggedIn) return false
        deviceId = settings.deviceId()
        configure(creds.serverUrl, creds.token, creds.userId)
        return true
    }

    /** Checks the server is reachable before any credentials exist. */
    suspend fun serverStatus(serverInput: String): ServerStatus {
        val server = normalizeServerUrl(serverInput)
        deviceId = settings.deviceId()
        // A successful public-info call proves the URL points at a Jellyfin server.
        buildApi(server).publicInfo()
        // Jellyfin authenticates with username/password; advertise local auth only.
        return ServerStatus(isInit = true, authMethods = listOf("local"))
    }

    /**
     * Jellyfin uses username/password (or Quick Connect); SSO is not wired up.
     * Retained because the LoginScreen SSO branch still references it (that
     * branch never renders, since [serverStatus] reports no OpenID support).
     */
    suspend fun startOidcLogin(serverInput: String): String {
        throw UnsupportedOperationException("Single sign-on is not supported")
    }

    suspend fun login(serverInput: String, username: String, password: String) {
        val server = normalizeServerUrl(serverInput)
        deviceId = settings.deviceId()
        val anonymous = buildApi(server)
        val result = anonymous.authenticate(JfAuthRequest(username = username, pw = password))
        if (result.accessToken.isBlank() || result.user.id.isBlank()) {
            throw IllegalStateException("Login failed")
        }
        configure(server, result.accessToken, result.user.id)
        settings.saveLogin(server, result.accessToken, result.user.id, result.user.name.ifBlank { username })
    }

    suspend fun logout() {
        api = null
        serverUrl = ""
        token = ""
        userId = ""
        itemCache.clear()
        progressByTrack.clear()
        albumsCache = emptyList()
        latestCache = emptyList()
        songsCache = emptyList()
        topPicksCache = emptyList()
        mixesCache = emptyList()
        librariesCache = emptyList()
        settings.clear()
    }

    /** All music libraries on the server (falls back to every view if none are tagged music). */
    suspend fun libraries(forceRefresh: Boolean = false): List<Library> {
        if (!forceRefresh && librariesCache.isNotEmpty()) return librariesCache
        val result = try {
            val views = requireApi().views(requireUserId()).items
            val music = views.filter { it.collectionType == "music" }
            val picked = music.ifEmpty { views }
            picked.map { Library(id = it.id, name = it.name, mediaType = it.collectionType ?: "") }
                .also { diskCacheWrite("libraries.json", it) }
        } catch (e: Exception) {
            diskCacheRead<List<Library>>("libraries.json") ?: throw e
        }
        librariesCache = result
        return result
    }

    /** Returns the active library id, defaulting to the first music library. */
    suspend fun activeLibraryId(): String {
        val saved = settings.snapshot().libraryId
        if (saved.isNotBlank()) return saved
        val all = libraries()
        val pick = all.firstOrNull { it.mediaType == "music" } ?: all.firstOrNull()
            ?: throw IllegalStateException("No music libraries found on this server")
        settings.saveLibraryId(pick.id)
        return pick.id
    }

    suspend fun activeLibrary(): Library? {
        val id = activeLibraryId()
        return libraries().firstOrNull { it.id == id }
    }

    /** Switches the active library and clears caches so the next loads use it. */
    suspend fun selectLibrary(libraryId: String) {
        settings.saveLibraryId(libraryId)
        albumsCache = emptyList()
        latestCache = emptyList()
        songsCache = emptyList()
        topPicksCache = emptyList()
        mixesCache = emptyList()
        itemCache.clear()
        progressByTrack.clear()
        runCatching {
            cacheDir?.listFiles()
                ?.filterNot { it.name == "libraries.json" }
                ?.forEach { it.delete() }
        }
    }

    /** Every album in the active library, sorted by name. */
    suspend fun podcasts(forceRefresh: Boolean = false): List<LibraryItemSummary> {
        if (!forceRefresh && albumsCache.isNotEmpty()) return albumsCache
        val items = try {
            val libraryId = activeLibraryId()
            requireApi().items(
                userId = requireUserId(),
                parentId = libraryId,
                includeItemTypes = "MusicAlbum",
                recursive = true,
                sortBy = "SortName",
                sortOrder = "Ascending",
            ).items.map { it.toSummary() }.also { diskCacheWrite("albums.json", it) }
        } catch (e: Exception) {
            diskCacheRead<List<LibraryItemSummary>>("albums.json") ?: throw e
        }
        albumsCache = items
        return items
    }

    /** An album with its full track list. */
    suspend fun podcast(itemId: String, forceRefresh: Boolean = false): LibraryItemExpanded {
        if (!forceRefresh) itemCache[itemId]?.let { return it }
        val item = try {
            val uid = requireUserId()
            val album = requireApi().item(uid, itemId)
            val tracks = requireApi().items(
                userId = uid,
                parentId = itemId,
                includeItemTypes = "Audio",
                recursive = false,
                sortBy = "ParentIndexNumber,IndexNumber,SortName",
                sortOrder = "Ascending",
            ).items
            val episodes = tracks.map { toEpisode(it, itemId) }
            LibraryItemExpanded(
                id = itemId,
                media = PodcastMedia(
                    metadata = PodcastMetadata(
                        title = album.name,
                        author = album.albumArtist,
                        description = album.overview,
                    ),
                    episodes = episodes,
                    duration = episodes.sumOf { it.audioTrack?.duration ?: 0.0 },
                ),
            ).also { diskCacheWrite("item_$itemId.json", it) }
        } catch (e: Exception) {
            diskCacheRead<LibraryItemExpanded>("item_$itemId.json") ?: throw e
        }
        itemCache[itemId] = item
        return item
    }

    suspend fun progress(itemId: String, episodeId: String, maxAgeMs: Long = 30_000): MediaProgress? =
        progressByTrack[episodeId]

    /** Whole-album resume isn't a music concept; always null. */
    suspend fun bookProgress(itemId: String, maxAgeMs: Long = 30_000): MediaProgress? = null

    data class InProgressEpisode(
        val podcast: LibraryItemExpanded,
        val episode: PodcastEpisode,
        val progress: Double,
    )

    /** Most recently played tracks, newest first. */
    suspend fun continueListening(limit: Int = 15, forceRefresh: Boolean = false): List<InProgressEpisode> {
        val tracks = runCatching {
            requireApi().items(
                userId = requireUserId(),
                includeItemTypes = "Audio",
                recursive = true,
                sortBy = "DatePlayed",
                sortOrder = "Descending",
                filters = "IsPlayed",
                limit = limit,
            ).items
        }.getOrElse { return emptyList() }
        return tracks.mapNotNull { track ->
            val albumId = track.albumId ?: return@mapNotNull null
            val episode = toEpisode(track, albumId)
            val mp = progressByTrack[track.id]
            val fraction = mp?.progress?.takeIf { it > 0.0 } ?: 1.0
            InProgressEpisode(
                podcast = minimalAlbum(track, episode),
                episode = episode,
                progress = fraction.coerceIn(0.0, 1.0),
            )
        }
    }

    /** Most recently added albums in the library. */
    suspend fun recentlyAdded(limit: Int = 12, forceRefresh: Boolean = false): List<LibraryItemSummary> =
        podcasts(forceRefresh).sortedByDescending { it.addedAt }.take(limit)

    /** Every song in the active library, sorted by name. */
    suspend fun songs(forceRefresh: Boolean = false): List<PodcastEpisode> {
        if (!forceRefresh && songsCache.isNotEmpty()) return songsCache
        val result = try {
            requireApi().items(
                userId = requireUserId(),
                parentId = activeLibraryId(),
                includeItemTypes = "Audio",
                recursive = true,
                sortBy = "SortName",
                sortOrder = "Ascending",
            ).items.map { toEpisode(it, it.albumId ?: "") }
                .also { diskCacheWrite("songs.json", it) }
        } catch (e: Exception) {
            diskCacheRead<List<PodcastEpisode>>("songs.json") ?: throw e
        }
        songsCache = result
        return result
    }

    /** The albums the user plays most, for the Home "Top Picks" shelf. */
    suspend fun topPicks(limit: Int = 10, forceRefresh: Boolean = false): List<LibraryItemSummary> {
        if (!forceRefresh && topPicksCache.isNotEmpty()) return topPicksCache
        val played = runCatching {
            requireApi().items(
                userId = requireUserId(),
                parentId = activeLibraryId(),
                includeItemTypes = "MusicAlbum",
                recursive = true,
                sortBy = "PlayCount",
                sortOrder = "Descending",
                limit = limit,
            ).items.map { it.toSummary() }
        }.getOrDefault(emptyList())
        // A fresh library has no play history yet; rotate a daily selection instead.
        val result = played.ifEmpty {
            podcasts(forceRefresh).shuffled(kotlin.random.Random(daySeed())).take(limit)
        }
        topPicksCache = result
        return result
    }

    /** A generated Apple Music-style mix: a themed queue built from the library. */
    data class Mix(
        val id: String,
        val title: String,
        val subtitle: String,
        val songs: List<PodcastEpisode>,
    )

    /**
     * "Made for You" mixes generated locally: one per major genre, artist
     * essentials to fill, plus a Discovery Mix. Reshuffled daily (stable seed
     * per day so the shelf doesn't churn while browsing).
     */
    suspend fun madeForYou(count: Int = 6, forceRefresh: Boolean = false): List<Mix> {
        if (!forceRefresh && mixesCache.isNotEmpty()) return mixesCache
        val all = runCatching { songs(forceRefresh) }.getOrDefault(emptyList())
        if (all.isEmpty()) return emptyList()
        val seed = daySeed()
        val mixes = mutableListOf<Mix>()

        val byGenre = all.flatMap { song -> song.genres.map { it.trim() to song } }
            .filter { it.first.isNotBlank() }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 4 }
            .entries.sortedByDescending { it.value.size }
        for ((genre, tracks) in byGenre) {
            if (mixes.size >= count - 1) break
            mixes += Mix(
                id = "genre:$genre",
                title = "$genre Mix",
                subtitle = "${tracks.size} songs in your library",
                songs = tracks.shuffled(kotlin.random.Random(seed + genre.hashCode())).take(25),
            )
        }
        if (mixes.size < count - 1) {
            val byArtist = all.filterNot { it.subtitle.isNullOrBlank() }
                .groupBy { it.subtitle.orEmpty() }
                .filterValues { it.size >= 4 }
                .entries.sortedByDescending { it.value.size }
            for ((artist, tracks) in byArtist) {
                if (mixes.size >= count - 1) break
                mixes += Mix(
                    id = "artist:$artist",
                    title = "$artist Essentials",
                    subtitle = "The best of $artist",
                    songs = tracks.shuffled(kotlin.random.Random(seed + artist.hashCode())).take(25),
                )
            }
        }
        mixes += Mix(
            id = "discovery",
            title = "Discovery Mix",
            subtitle = "Fresh picks from your library",
            songs = all.shuffled(kotlin.random.Random(seed)).take(25),
        )
        mixesCache = mixes
        return mixes
    }

    suspend fun mix(id: String): Mix? = madeForYou().firstOrNull { it.id == id }

    /** Newest tracks added to the library, latest first. */
    suspend fun latestEpisodes(limit: Int = 75, forceRefresh: Boolean = false): List<PodcastEpisode> {
        if (!forceRefresh && latestCache.isNotEmpty()) return latestCache
        val result = try {
            requireApi().latest(requireUserId(), includeItemTypes = "Audio", limit = limit)
                .map { toEpisode(it, it.albumId ?: "") }
                .also { diskCacheWrite("latest.json", it) }
        } catch (e: Exception) {
            diskCacheRead<List<PodcastEpisode>>("latest.json") ?: throw e
        }
        latestCache = result
        return result
    }

    /** Jellyfin exposes no per-user listening total; return an empty stat block. */
    suspend fun listeningStats(): ListeningStats = ListeningStats()

    /**
     * Album/artist search plus track-title search, used by the search UI and
     * Android Auto browse search / voice queries.
     */
    suspend fun search(
        query: String,
        maxPodcastFetches: Int = 20,
    ): Pair<List<LibraryItemSummary>, List<Pair<LibraryItemExpanded, PodcastEpisode>>> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList<LibraryItemSummary>() to emptyList()
        val uid = requireUserId()
        val albums = runCatching {
            requireApi().items(
                userId = uid,
                includeItemTypes = "MusicAlbum",
                recursive = true,
                searchTerm = needle,
                limit = 40,
            ).items.map { it.toSummary() }
        }.getOrDefault(emptyList())
        val trackMatches = runCatching {
            requireApi().items(
                userId = uid,
                includeItemTypes = "Audio",
                recursive = true,
                searchTerm = needle,
                limit = 40,
            ).items.map { track ->
                val albumId = track.albumId ?: ""
                val episode = toEpisode(track, albumId)
                minimalAlbum(track, episode) to episode
            }
        }.getOrDefault(emptyList())
        return albums to trackMatches
    }

    suspend fun updateProgress(itemId: String, episodeId: String, currentTimeSec: Double, durationSec: Double) {
        if (episodeId.isBlank()) return
        runCatching {
            requireApi().reportProgress(
                JfProgressBody(
                    itemId = episodeId,
                    positionTicks = secToTicks(currentTimeSec),
                    isPaused = true,
                ),
            )
        }
        if (durationSec > 0 && currentTimeSec / durationSec > 0.98) {
            runCatching { requireApi().markPlayed(requireUserId(), episodeId) }
        }
    }

    /** Resets a track's played state back to unplayed. */
    suspend fun resetProgress(itemId: String, episodeId: String, durationSec: Double = 0.0) {
        if (episodeId.isBlank()) return
        requireApi().markUnplayed(requireUserId(), episodeId)
        progressByTrack.remove(episodeId)
        _progressRevision.value += 1
    }

    /** Marks a track played, or back to unplayed. */
    suspend fun setFinished(
        itemId: String,
        episodeId: String,
        finished: Boolean,
        durationSec: Double = 0.0,
    ) {
        if (episodeId.isBlank()) return
        if (finished) {
            requireApi().markPlayed(requireUserId(), episodeId)
            progressByTrack[episodeId] = MediaProgress(
                libraryItemId = itemId,
                episodeId = episodeId,
                duration = durationSec,
                currentTime = durationSec,
                progress = 1.0,
                isFinished = true,
            )
        } else {
            requireApi().markUnplayed(requireUserId(), episodeId)
            progressByTrack.remove(episodeId)
        }
        _progressRevision.value += 1
    }

    fun coverUrl(itemId: String): String =
        "$serverUrl/Items/$itemId/Images/Primary?maxHeight=600&api_key=$token"

    fun streamUrl(itemId: String, episode: PodcastEpisode): String? {
        if (episode.id.isBlank()) return null
        return "$serverUrl/Audio/${episode.id}/stream?static=true&api_key=$token"
    }

    /** Appends the API key to a server-relative content URL. */
    fun tokenizedUrl(contentUrl: String): String {
        val separator = if (contentUrl.contains('?')) "&" else "?"
        return "$serverUrl$contentUrl${separator}api_key=$token"
    }

    // ---- Jellyfin -> app-model mapping ------------------------------------

    private fun JfItem.toSummary() = LibraryItemSummary(
        id = id,
        addedAt = parseJfDate(dateCreated),
        media = MediaSummary(
            metadata = PodcastMetadata(title = name, author = albumArtist),
            numEpisodes = childCount ?: 0,
        ),
    )

    private fun toEpisode(track: JfItem, albumId: String): PodcastEpisode {
        recordProgress(track, albumId)
        val artist = track.artists.joinToString(", ").ifBlank { track.albumArtist }
        return PodcastEpisode(
            id = track.id,
            libraryItemId = albumId,
            title = track.name,
            subtitle = artist,
            description = track.overview,
            publishedAt = parseJfDate(track.premiereDate ?: track.dateCreated).takeIf { it > 0 },
            season = track.parentIndexNumber?.toString(),
            episode = track.indexNumber?.toString(),
            genres = track.genres,
            audioTrack = AudioTrack(
                duration = ticksToSec(track.runTimeTicks),
                contentUrl = "/Audio/${track.id}/stream?static=true",
            ),
        )
    }

    private fun minimalAlbum(track: JfItem, episode: PodcastEpisode) = LibraryItemExpanded(
        id = track.albumId ?: "",
        media = PodcastMedia(
            metadata = PodcastMetadata(title = track.album, author = track.albumArtist),
            episodes = listOf(episode),
        ),
    )

    private fun recordProgress(track: JfItem, albumId: String) {
        val ud = track.userData ?: return
        val durationSec = ticksToSec(track.runTimeTicks)
        progressByTrack[track.id] = MediaProgress(
            libraryItemId = albumId,
            episodeId = track.id,
            duration = durationSec,
            currentTime = ticksToSec(ud.playbackPositionTicks),
            progress = ud.playedPercentage?.div(100.0) ?: if (ud.played) 1.0 else 0.0,
            isFinished = ud.played,
            lastUpdate = parseJfDate(ud.lastPlayedDate),
        )
    }

    private fun requireApi(): AbsApi =
        api ?: throw IllegalStateException("Not logged in")

    private fun requireUserId(): String =
        userId.ifBlank { throw IllegalStateException("Not logged in") }

    private inline fun <reified T> diskCacheWrite(name: String, value: T) {
        runCatching { cacheDir?.resolve(name)?.writeText(json.encodeToString(value)) }
    }

    private inline fun <reified T> diskCacheRead(name: String): T? = runCatching {
        cacheDir?.resolve(name)?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    /**
     * Builds a Jellyfin client. Every request carries the MediaBrowser
     * authorization header (with the access token once we have one).
     */
    private fun buildApi(serverUrl: String): AbsApi {
        val authValue = buildString {
            append("MediaBrowser Client=\"Sonofin\", Device=\"Android\"")
            append(", DeviceId=\"${deviceId.ifBlank { "sonofin" }}\"")
            append(", Version=\"$CLIENT_VERSION\"")
            if (token.isNotBlank()) append(", Token=\"$token\"")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", authValue)
                    .header("X-Emby-Authorization", authValue)
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("${serverUrl.trimEnd('/')}/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AbsApi::class.java)
    }

    companion object {
        private const val CLIENT_VERSION = "0.1.0"

        /** A seed that changes once a day, so generated shelves are stable while browsing. */
        private fun daySeed(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

        private fun ticksToSec(ticks: Long?): Double = (ticks ?: 0L) / 10_000_000.0

        private fun secToTicks(seconds: Double): Long = (seconds * 10_000_000.0).toLong()

        private fun parseJfDate(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            return runCatching { Instant.parse(value).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }

        fun normalizeServerUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            return url
        }
    }
}
