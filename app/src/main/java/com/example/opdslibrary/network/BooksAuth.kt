package com.example.opdslibrary.network

import com.example.opdslibrary.BuildConfig
import okhttp3.Credentials
import okhttp3.Interceptor
import java.net.HttpURLConnection

/**
 * Built-in Basic auth for the self-hosted inpx-web libraries (books.darkclad.org).
 *
 * The gateway requires the password only on traffic arriving through the Cloudflare tunnel
 * (away from home); on the LAN the header is ignored. Credentials come from BuildConfig,
 * supplied at build time by publish-opds.ps1 from the `books-opds` vault secret; local/dev
 * builds have none and fall back to the app's normal 401 login prompt.
 *
 * Applied to every HTTP client (feeds, downloads, covers), and only for this host, so the
 * password is never sent anywhere else. OkHttp drops the header on cross-host redirects.
 */
object BooksAuth {
    private const val HOST = "books.darkclad.org"

    private val header: String? =
        if (BuildConfig.BOOKS_OPDS_USER.isNotEmpty() && BuildConfig.BOOKS_OPDS_PASS.isNotEmpty()) {
            Credentials.basic(BuildConfig.BOOKS_OPDS_USER, BuildConfig.BOOKS_OPDS_PASS)
        } else null

    private fun appliesTo(host: String?): Boolean = header != null && host.equals(HOST, ignoreCase = true)

    /** OkHttp interceptor; leaves requests that already carry user-entered credentials alone. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (appliesTo(request.url.host) && request.header("Authorization") == null) {
            chain.proceed(request.newBuilder().header("Authorization", header!!).build())
        } else {
            chain.proceed(request)
        }
    }

    /** Same for plain HttpURLConnection callers; call before connect(). */
    fun apply(connection: HttpURLConnection) {
        if (appliesTo(connection.url.host)) {
            connection.setRequestProperty("Authorization", header)
        }
    }
}
