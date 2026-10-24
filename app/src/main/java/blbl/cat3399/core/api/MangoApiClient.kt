package blbl.cat3399.core.api

import blbl.cat3399.core.net.CookieStore
import blbl.cat3399.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared authenticated HTTP transport for Mango account and history APIs. */
internal class MangoApiClient(cookies: CookieStore) {
    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun getJson(
        apiOrigin: String,
        path: String,
        params: Map<String, String>,
    ): JSONObject {
        val url = apiOrigin.toHttpUrl().newBuilder().addPathSegments(path.removePrefix("/")).apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", SITE_ORIGIN)
            .header("Referer", "$SITE_ORIGIN/")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val response = client.newCall(request).await()
        response.use {
            val body = withContext(Dispatchers.IO) { it.body?.string().orEmpty() }
            if (!it.isSuccessful) throw IOException("芒果请求 HTTP ${it.code}")
            return withContext(Dispatchers.Default) { JSONObject(body) }
        }
    }

    private companion object {
        const val SITE_ORIGIN = "https://www.mgtv.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
