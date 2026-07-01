package app.shelfie.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// App-facing domain models
//
// Sonofin is a music client, but it grew out of a podcast app, so the internal
// vocabulary still uses "podcast/episode" type names. The mapping is:
//
//   LibraryItemSummary / LibraryItemExpanded  ->  a music album
//   PodcastEpisode                            ->  a track/song on that album
//   PodcastMetadata.author                    ->  the album artist
//
// These classes are the stable surface the UI and playback service depend on;
// the repository maps Jellyfin's API responses onto them.
// ---------------------------------------------------------------------------

@Serializable
data class ServerStatus(
    val isInit: Boolean = true,
    val authMethods: List<String> = emptyList(),
    val authFormData: AuthFormData? = null,
) {
    val supportsLocal: Boolean get() = authMethods.isEmpty() || authMethods.contains("local")
    val supportsOpenId: Boolean get() = authMethods.contains("openid")
}

@Serializable
data class AuthFormData(
    val authLoginCustomMessage: String? = null,
    val authOpenIDButtonText: String? = null,
    val authOpenIDAutoLaunch: Boolean = false,
)

@Serializable
data class MediaProgress(
    val id: String = "",
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
)

@Serializable
data class Library(
    val id: String = "",
    val name: String = "",
    val mediaType: String = "",
)

@Serializable
data class LibraryItemSummary(
    val id: String = "",
    val addedAt: Long = 0,
    val media: MediaSummary = MediaSummary(),
)

@Serializable
data class MediaSummary(
    val metadata: PodcastMetadata = PodcastMetadata(),
    /** Number of tracks on the album. */
    val numEpisodes: Int = 0,
)

@Serializable
data class LibraryItemExpanded(
    val id: String = "",
    val media: PodcastMedia = PodcastMedia(),
)

@Serializable
data class PodcastMedia(
    val metadata: PodcastMetadata = PodcastMetadata(),
    /** The album's tracks, in track order. */
    val episodes: List<PodcastEpisode> = emptyList(),
    // Retained for compatibility with the audiobook-style track path; unused for music.
    val tracks: List<BookTrack> = emptyList(),
    val duration: Double = 0.0,
)

@Serializable
data class PodcastMetadata(
    val title: String? = null,
    val author: String? = null,
    val authorName: String? = null,
    val description: String? = null,
) {
    val displayAuthor: String? get() = author ?: authorName
}

@Serializable
data class BookTrack(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String? = null,
    val contentUrl: String? = null,
)

@Serializable
data class PodcastEpisode(
    val id: String = "",
    val libraryItemId: String = "",
    val title: String? = null,
    /** The track artist(s). */
    val subtitle: String? = null,
    val description: String? = null,
    val publishedAt: Long? = null,
    val pubDate: String? = null,
    /** Disc number, if the album spans multiple discs. */
    val season: String? = null,
    /** Track number on the disc. */
    val episode: String? = null,
    val genres: List<String> = emptyList(),
    val audioFile: AudioFile? = null,
    val audioTrack: AudioTrack? = null,
)

@Serializable
data class AudioFile(
    val ino: String = "",
    val duration: Double = 0.0,
    val mimeType: String? = null,
)

@Serializable
data class AudioTrack(
    val duration: Double = 0.0,
    val contentUrl: String? = null,
)

@Serializable
data class ListeningStats(
    val totalTime: Double = 0.0,
    val today: Double = 0.0,
)

// ---------------------------------------------------------------------------
// Jellyfin API DTOs (https://api.jellyfin.org/)
//
// Property names are camelCase with @SerialName mapping to Jellyfin's
// PascalCase JSON. Only the fields Sonofin needs are modelled.
// ---------------------------------------------------------------------------

@Serializable
data class JfPublicInfo(
    @SerialName("ServerName") val serverName: String = "",
    @SerialName("Version") val version: String = "",
    @SerialName("Id") val id: String = "",
    @SerialName("StartupWizardCompleted") val startupWizardCompleted: Boolean = true,
)

@Serializable
data class JfAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

@Serializable
data class JfAuthResult(
    @SerialName("User") val user: JfUser = JfUser(),
    @SerialName("AccessToken") val accessToken: String = "",
)

@Serializable
data class JfUser(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

@Serializable
data class JfViewsResponse(
    @SerialName("Items") val items: List<JfView> = emptyList(),
)

@Serializable
data class JfView(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
)

@Serializable
data class JfItemsResponse(
    @SerialName("Items") val items: List<JfItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class JfItem(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("UserData") val userData: JfUserData? = null,
)

@Serializable
data class JfUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

@Serializable
data class JfProgressBody(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean = true,
    @SerialName("PlayMethod") val playMethod: String = "DirectStream",
    @SerialName("EventName") val eventName: String = "timeupdate",
)
