package app.shelfie

import android.app.Application
import app.shelfie.data.AbsRepository
import app.shelfie.data.InsecureTls
import app.shelfie.data.SettingsStore
import app.shelfie.download.DownloadCenter
import app.shelfie.history.HistoryStore
import app.shelfie.pin.PinStore
import app.shelfie.playlist.PlaylistStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import java.io.File
import okhttp3.OkHttpClient

class ShelfieApp : Application(), ImageLoaderFactory {

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val repository: AbsRepository by lazy {
        AbsRepository(settings, cacheDir = File(filesDir, "apicache"))
    }
    val downloads: DownloadCenter by lazy { DownloadCenter(this, repository, settings) }
    val playlist: PlaylistStore by lazy { PlaylistStore(this) }
    val pins: PinStore by lazy { PinStore(this) }
    val history: HistoryStore by lazy { HistoryStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Self-hosted servers rarely have CA-signed certificates; accept
        // self-signed TLS everywhere (streaming included).
        InsecureTls.installGlobal()
    }

    /** Coil loader that also accepts self-signed certificates for cover art. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { InsecureTls.apply(OkHttpClient.Builder()).build() }
        .build()
}
