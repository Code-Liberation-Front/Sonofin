package app.shelfie.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit description of the subset of the Jellyfin REST API that Sonofin uses.
 * Authentication is supplied by an OkHttp interceptor that adds the
 * `Authorization: MediaBrowser ...` header (see the repository).
 */
interface AbsApi {

    /** Unauthenticated reachability/handshake check. */
    @GET("System/Info/Public")
    suspend fun publicInfo(): JfPublicInfo

    @POST("Users/AuthenticateByName")
    suspend fun authenticate(@Body body: JfAuthRequest): JfAuthResult

    /** The user's library views; music libraries have CollectionType "music". */
    @GET("Users/{userId}/Views")
    suspend fun views(@Path("userId") userId: String): JfViewsResponse

    @GET("Items")
    suspend fun items(
        @Query("UserId") userId: String,
        @Query("ParentId") parentId: String? = null,
        @Query("IncludeItemTypes") includeItemTypes: String? = null,
        @Query("Recursive") recursive: Boolean = true,
        @Query("SortBy") sortBy: String? = null,
        @Query("SortOrder") sortOrder: String? = null,
        @Query("Filters") filters: String? = null,
        @Query("SearchTerm") searchTerm: String? = null,
        /** Name-based artist filter (pipe-delimited). */
        @Query("Artists") artists: String? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        // Only valid ItemFields enum values; AlbumArtist/Artists are returned by default.
        @Query("Fields") fields: String = "DateCreated,Overview,ChildCount,Genres",
    ): JfItemsResponse

    /** Album artists in a library, paged and searchable server-side. */
    @GET("Artists/AlbumArtists")
    suspend fun albumArtists(
        @Query("UserId") userId: String,
        @Query("ParentId") parentId: String? = null,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
    ): JfItemsResponse

    @GET("Users/{userId}/Items/{itemId}")
    suspend fun item(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
    ): JfItem

    /** Recently added items. Returns a bare JSON array, not a wrapped response. */
    @GET("Users/{userId}/Items/Latest")
    suspend fun latest(
        @Path("userId") userId: String,
        @Query("IncludeItemTypes") includeItemTypes: String = "Audio",
        @Query("Limit") limit: Int = 50,
        @Query("Fields") fields: String = "DateCreated",
    ): List<JfItem>

    @POST("Users/{userId}/PlayedItems/{itemId}")
    suspend fun markPlayed(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
    )

    @DELETE("Users/{userId}/PlayedItems/{itemId}")
    suspend fun markUnplayed(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
    )

    @POST("Sessions/Playing/Progress")
    suspend fun reportProgress(@Body body: JfProgressBody)

    /** Embedded or sidecar lyrics for a song (404 when none exist). */
    @GET("Audio/{itemId}/Lyrics")
    suspend fun lyrics(@Path("itemId") itemId: String): JfLyrics
}
