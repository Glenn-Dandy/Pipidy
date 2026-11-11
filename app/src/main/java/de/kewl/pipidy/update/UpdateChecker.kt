package de.kewl.pipidy.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub coordinates, also used for the links on the about page. */
object Repo {
    const val OWNER = "Glenn-Dandy"
    const val NAME = "Pipidy"
    const val URL = "https://github.com/$OWNER/$NAME"
    const val LATEST_RELEASE_URL = "$URL/releases/latest"
    const val ISSUES_NEW_URL = "$URL/issues/new"
    const val SUPPORT_URL = "https://paypal.me/GlennDandy"
    const val WEBSITE_LABEL = "wozise.de"
    const val WEBSITE_URL = "https://$WEBSITE_LABEL"
    const val API_LATEST = "https://api.github.com/repos/$OWNER/$NAME/releases/latest"
}

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val version: String, val downloadUrl: String?, val releaseUrl: String) : UpdateResult
    data object Failed : UpdateResult
}

object UpdateChecker {

    /** Asks GitHub for the latest release and compares it with [currentVersion], e.g. "0.3.0". */
    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val release = JSONObject(fetch(Repo.API_LATEST) ?: return@withContext UpdateResult.Failed)
            val version = release.optString("tag_name").removePrefix("v")
            if (version.isBlank()) return@withContext UpdateResult.Failed
            if (compareVersions(version, currentVersion) <= 0) {
                UpdateResult.UpToDate
            } else {
                UpdateResult.Available(version, apkUrl(release), release.optString("html_url", Repo.LATEST_RELEASE_URL))
            }
        } catch (_: Exception) {
            UpdateResult.Failed
        }
    }

    private fun fetch(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Pipidy")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun apkUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url").ifEmpty { null }
            }
        }
        return null
    }

    /** Numeric comparison of "1.2.3" versions, positive when [a] is newer. A suffix like "-beta" makes it older. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        val sa = a.substringAfter('-', "")
        val sb = b.substringAfter('-', "")
        return when {
            sa == sb -> 0
            sa.isEmpty() -> 1
            sb.isEmpty() -> -1
            else -> sa.compareTo(sb)
        }
    }
}
