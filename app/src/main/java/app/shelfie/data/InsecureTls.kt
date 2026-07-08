package app.shelfie.data

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Self-hosted Jellyfin servers usually run plain HTTP or a self-signed
 * certificate rather than a CA-signed one, so the app accepts any TLS
 * certificate instead of failing the connection.
 */
object InsecureTls {

    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }.socketFactory
    }

    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    /** Relaxes certificate checks on an OkHttp client (API, downloads, covers). */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .sslSocketFactory(socketFactory, trustManager)
        .hostnameVerifier(hostnameVerifier)

    /**
     * Relaxes the process-wide HttpsURLConnection defaults, which is what
     * ExoPlayer's HTTP data source uses for streaming.
     */
    fun installGlobal() {
        HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
    }
}
