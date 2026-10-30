package br.com.minhacorrida.update

import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.update.AppRelease
import br.com.minhacorrida.core.update.ReleaseNotesParser
import br.com.minhacorrida.core.update.ReleaseSource
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta a release "ultima-versao" no GitHub. Deve ser chamada fora da thread principal.
 *
 * @param apiUrl endpoint da API de releases do repositório.
 */
class GitHubReleaseSource(
    private val apiUrl: String,
    private val logger: AppLogger,
) : ReleaseSource {

    override fun latest(): AppRelease? {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.warn(TAG, "GitHub respondeu HTTP ${connection.responseCode}")
                return null
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val versionCode = ReleaseNotesParser.versionCode(json.optString("body")) ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            val apkUrl = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return AppRelease(versionCode, apkUrl)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "GitHubReleaseSource"
        const val TIMEOUT_MS = 8_000
    }
}
