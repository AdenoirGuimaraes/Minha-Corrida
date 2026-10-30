package br.com.minhacorrida.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.update.AppRelease
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Baixa o APK novo e abre o instalador do Android (o motorista confirma com um toque;
 * o Android não permite instalação silenciosa fora da Play Store).
 */
class ApkUpdateInstaller(
    context: Context,
    private val logger: AppLogger,
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param onProgress percentual 0–100, na thread principal.
     * @param onFinished arquivo baixado, ou null em caso de falha, na thread principal.
     */
    fun download(release: AppRelease, onProgress: (Int) -> Unit, onFinished: (File?) -> Unit) {
        executor.execute {
            val file = try {
                downloadTo(release.apkUrl, onProgress)
            } catch (e: Exception) {
                logger.error(TAG, "Falha ao baixar ${release.apkUrl}", e)
                null
            }
            mainHandler.post { onFinished(file) }
        }
    }

    /** No Android 8+ o usuário precisa liberar "Instalar apps desconhecidos" para este app. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}")),
            )
        } catch (e: Exception) {
            logger.error(TAG, "Não foi possível abrir a configuração de instalação", e)
        }
    }

    fun install(context: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            logger.error(TAG, "Não foi possível abrir o instalador", e)
        }
    }

    fun shutdown() = executor.shutdownNow()

    // Baixa para um arquivo temporário e só troca pelo definitivo se completar.
    private fun downloadTo(url: String, onProgress: (Int) -> Unit): File {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val partial = File(dir, "MinhaCorrida.apk.part")
        val target = File(dir, "MinhaCorrida.apk")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            var lastPercent = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                mainHandler.post { onProgress(percent) }
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        target.delete()
        check(partial.renameTo(target)) { "Não foi possível finalizar o arquivo" }
        return target
    }

    private companion object {
        const val TAG = "ApkUpdateInstaller"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val TIMEOUT_MS = 15_000
        const val BUFFER_SIZE = 64 * 1024
    }
}
