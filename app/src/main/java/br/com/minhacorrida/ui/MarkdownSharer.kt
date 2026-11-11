package br.com.minhacorrida.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Grava um texto Markdown em cache/reports e abre o "Compartilhar" do Android (ex.: anexar no
 * app do Claude). Usado pelo relatório de corridas e pelo relatório de diagnóstico.
 */
object MarkdownSharer {

    /** @throws Exception se não conseguir gravar ou abrir o compartilhar (quem chama avisa o motorista). */
    fun share(activity: Activity, fileName: String, markdown: String, chooserTitle: String) {
        val dir = File(activity.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeText(markdown) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        activity.startActivity(Intent.createChooser(send, chooserTitle))
    }
}
