package br.com.minhacorrida.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import br.com.minhacorrida.R
import br.com.minhacorrida.core.backup.BackupPolicy
import br.com.minhacorrida.infra.AppBackup
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService

/**
 * Seção "Backup" das Configurações:
 * - "Salvar backup": o Android pergunta onde salvar (Google Drive → pasta Minha Corrida, ou o celular);
 * - "Enviar backup": abre o Compartilhar (Gmail, WhatsApp, Drive…);
 * - "Restaurar backup": escolhe o arquivo, mostra o que tem dentro, confirma, troca os dados e reinicia o app.
 * Mostra quando foi o último backup.
 */
class BackupSection(
    private val activity: Activity,
    private val backup: AppBackup,
    private val io: ExecutorService,
    private val appVersion: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val meta = activity.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
    private val tvLast: TextView = activity.findViewById(R.id.tvBackupLast)

    fun bind() {
        activity.findViewById<Button>(R.id.btnBackupSave).setOnClickListener { askWhereToSave() }
        activity.findViewById<Button>(R.id.btnBackupShare).setOnClickListener { share() }
        activity.findViewById<Button>(R.id.btnBackupRestore).setOnClickListener { pickFileToRestore() }
        showLast()
    }

    /** Chamar no onActivityResult da Activity. @return true se o resultado era desta seção. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_SAVE && requestCode != REQUEST_RESTORE) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        if (requestCode == REQUEST_SAVE) save(uri) else readForRestore(uri)
        return true
    }

    // ---- Salvar / enviar ----

    private fun askWhereToSave() {
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, BackupPolicy.fileName(LocalDateTime.now()))
            activity.startActivityForResult(intent, REQUEST_SAVE)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Não abriu a escolha de pasta", e)
            toast(activity.getString(R.string.backup_failed))
        }
    }

    private fun save(uri: Uri) {
        io.execute {
            try {
                val json = backup.export(appVersion)
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                    ?: throw IllegalStateException("Sem acesso ao arquivo escolhido")
                main.post { markDone(); toast(activity.getString(R.string.backup_saved)) }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Falha ao salvar o backup", e)
                main.post { toast(activity.getString(R.string.backup_failed)) }
            }
        }
    }

    private fun share() {
        io.execute {
            try {
                val dir = File(activity.cacheDir, "backups").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // só o mais recente fica no celular
                val file = File(dir, BackupPolicy.fileName(LocalDateTime.now())).apply { writeText(backup.export(appVersion)) }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.backup_email_subject, file.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(file.name, uri)
                main.post {
                    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.backup_share_title)))
                    markDone()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Falha ao enviar o backup", e)
                main.post { toast(activity.getString(R.string.backup_failed)) }
            }
        }
    }

    // ---- Restaurar ----

    private fun pickFileToRestore() {
        try {
            // "*/*": no Drive o .json às vezes aparece com outro tipo.
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            activity.startActivityForResult(intent, REQUEST_RESTORE)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Não abriu a escolha do arquivo", e)
            toast(activity.getString(R.string.backup_failed))
        }
    }

    private fun readForRestore(uri: Uri) {
        io.execute {
            try {
                val json = activity.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw AppBackup.BackupException(activity.getString(R.string.backup_failed))
                val summary = backup.summarize(json)
                main.post { confirmRestore(json, summary.describe()) }
            } catch (e: AppBackup.BackupException) {
                main.post { toast(e.message ?: activity.getString(R.string.backup_failed)) }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Falha ao ler o backup", e)
                main.post { toast(activity.getString(R.string.backup_failed)) }
            }
        }
    }

    private fun confirmRestore(json: String, description: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_restore_title)
            .setMessage(activity.getString(R.string.backup_restore_message, description))
            .setPositiveButton(R.string.backup_restore_confirm) { _, _ -> restore(json) }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    private fun restore(json: String) {
        io.execute {
            try {
                backup.restore(json)
                main.post {
                    toast(activity.getString(R.string.backup_restored))
                    // Repositórios guardam dados em memória: reinicia o app para ler os restaurados.
                    main.postDelayed({ restartApp() }, RESTART_DELAY_MS)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Falha ao restaurar", e)
                main.post { toast(e.message ?: activity.getString(R.string.backup_failed)) }
            }
        }
    }

    private fun restartApp() {
        try {
            val intent = Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(intent)
        } finally {
            Runtime.getRuntime().exit(0)
        }
    }

    // ---- Último backup ----

    private fun markDone() {
        meta.edit().putString(KEY_LAST, LocalDateTime.now().toString()).apply()
        showLast()
    }

    private fun showLast() {
        val last = meta.getString(KEY_LAST, null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        tvLast.text = if (last == null) {
            activity.getString(R.string.backup_never)
        } else {
            activity.getString(R.string.backup_last, last.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "MC/Backup"
        private const val META_PREFS = "minha_corrida_backup_meta"
        private const val KEY_LAST = "last"
        private const val RESTART_DELAY_MS = 1_500L
        const val REQUEST_SAVE = 31
        const val REQUEST_RESTORE = 32
    }
}
