package br.com.minhacorrida.infra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import br.com.minhacorrida.core.backup.BackupPolicy
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.receipt.ReceiptImage
import br.com.minhacorrida.core.receipt.ReceiptStore
import java.io.File

/**
 * Fotos de comprovante na pasta do app ("comprovantes/<id do registro>.jpg"), reduzidas e
 * giradas na posição certa. Vão no backup. Fotos sem registro (abastecimento cancelado) somem
 * depois de um dia ([purgeOrphans]).
 */
class ReceiptPhotos(context: Context, private val logger: AppLogger) : ReceiptStore {

    private val appContext = context.applicationContext
    val dir: File get() = File(appContext.filesDir, BackupPolicy.RECEIPTS_DIR).apply { mkdirs() }

    override fun has(entryId: String): Boolean = file(entryId).exists()

    fun file(entryId: String) = File(dir, ReceiptImage.fileName(entryId))

    /** Link para abrir a foto no visualizador do Android. */
    fun uri(entryId: String): Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file(entryId))

    /**
     * Lê a foto de [source] (câmera ou galeria), reduz, corrige a rotação e grava como comprovante.
     * @throws Exception se a imagem não puder ser lida (quem chama avisa o motorista).
     */
    fun save(entryId: String, source: Uri) {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem ilegível" }
        val options = BitmapFactory.Options().apply { inSampleSize = ReceiptImage.sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Imagem ilegível")
        val (w, h) = ReceiptImage.scaledSize(decoded.width, decoded.height)
        var bitmap = if (w != decoded.width || h != decoded.height) Bitmap.createScaledBitmap(decoded, w, h, true) else decoded
        // Fotos da câmera vêm "deitadas" com a rotação anotada no EXIF.
        val degrees = runCatching {
            resolver.openInputStream(source)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees != 0f) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        }
        val target = file(entryId)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, ReceiptImage.JPEG_QUALITY, it) }
        if (!tmp.renameTo(target)) {
            target.writeBytes(tmp.readBytes())
            tmp.delete()
        }
        logger.debug(TAG, "Comprovante salvo (${target.length() / 1024} KB)")
    }

    fun delete(entryId: String) {
        runCatching { file(entryId).delete() }.onFailure { logger.error(TAG, "Falha ao apagar comprovante", it) }
    }

    /** Apaga fotos sem registro com mais de um dia. @param ids ids de abastecimentos e manutenções. */
    fun purgeOrphans(ids: Set<String>) {
        try {
            val limit = System.currentTimeMillis() - DAY_MS
            dir.listFiles()?.forEach { f ->
                val id = f.name.removeSuffix(".jpg")
                if (id !in ids && f.lastModified() < limit) f.delete()
            }
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao limpar comprovantes", e)
        }
    }

    private companion object {
        const val TAG = "Receipts"
        const val DAY_MS = 24 * 3_600_000L
    }
}
