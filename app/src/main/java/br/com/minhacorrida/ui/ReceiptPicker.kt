package br.com.minhacorrida.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import br.com.minhacorrida.R
import br.com.minhacorrida.infra.ReceiptPhotos
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * Botão "📷 Comprovante" dos registros (abastecimento e manutenção): tirar foto, escolher da
 * galeria, ver, trocar ou remover. A foto fica com o id do registro.
 */
class ReceiptPicker(
    private val activity: Activity,
    private val photos: ReceiptPhotos,
    private val io: ExecutorService,
) {
    private val main = Handler(Looper.getMainLooper())
    private var pendingId: String? = null
    private var pendingDone: (() -> Unit)? = null
    private var cameraUri: Uri? = null

    /** Texto do botão conforme já tem foto ou não. */
    fun label(entryId: String): String =
        activity.getString(if (photos.has(entryId)) R.string.receipt_attached else R.string.receipt_add)

    /** Abre as opções da foto. [onChanged] é chamado quando a foto entra ou sai. */
    fun choose(entryId: String, onChanged: () -> Unit) {
        val has = photos.has(entryId)
        val options = if (has) {
            arrayOf(R.string.receipt_view, R.string.receipt_camera, R.string.receipt_gallery, R.string.receipt_remove)
        } else {
            arrayOf(R.string.receipt_camera, R.string.receipt_gallery)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.receipt_title)
            .setItems(options.map { activity.getString(it) }.toTypedArray()) { _, which ->
                when (options[which]) {
                    R.string.receipt_view -> view(entryId)
                    R.string.receipt_camera -> camera(entryId, onChanged)
                    R.string.receipt_gallery -> gallery(entryId, onChanged)
                    R.string.receipt_remove -> { photos.delete(entryId); onChanged() }
                }
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** Registro excluído: a foto sai junto. */
    fun remove(entryId: String) = photos.delete(entryId)

    /** Chamar no onActivityResult da Activity. @return true se era desta tela. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CAMERA && requestCode != REQUEST_GALLERY) return false
        val id = pendingId
        val done = pendingDone
        pendingId = null
        pendingDone = null
        val source = if (requestCode == REQUEST_CAMERA) cameraUri else data?.data
        if (resultCode != Activity.RESULT_OK || id == null || source == null) return true
        io.execute {
            try {
                photos.save(id, source)
                main.post {
                    toast(activity.getString(R.string.receipt_saved))
                    done?.invoke()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Falha ao salvar comprovante", e)
                main.post { toast(activity.getString(R.string.receipt_failed)) }
            }
        }
        return true
    }

    private fun camera(entryId: String, onChanged: () -> Unit) {
        try {
            val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "comprovante.jpg").apply { delete() }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
            cameraUri = uri
            pendingId = entryId
            pendingDone = onChanged
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("comprovante", uri)
            activity.startActivityForResult(intent, REQUEST_CAMERA)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Câmera indisponível", e)
            toast(activity.getString(R.string.receipt_failed))
        }
    }

    private fun gallery(entryId: String, onChanged: () -> Unit) {
        try {
            pendingId = entryId
            pendingDone = onChanged
            activity.startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQUEST_GALLERY,
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Galeria indisponível", e)
            toast(activity.getString(R.string.receipt_failed))
        }
    }

    private fun view(entryId: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(photos.uri(entryId), "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Sem app para ver a foto", e)
            toast(activity.getString(R.string.receipt_failed))
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "MC/Receipt"
        const val REQUEST_CAMERA = 51
        const val REQUEST_GALLERY = 52
    }
}
