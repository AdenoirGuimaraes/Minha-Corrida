package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.backup.BackupPolicy
import br.com.minhacorrida.core.backup.BackupSummary
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/**
 * Gera e restaura o backup do app num único JSON:
 * {"format","version","createdAt","appVersion","prefs":{nome:{chave:{"t":tipo,"v":valor}}},"files":{nome:conteúdo}}.
 * O que entra é decidido por [BackupPolicy]. Restaurar troca TUDO o que está no backup
 * (o app precisa reiniciar depois, porque os repositórios guardam dados em memória).
 */
class AppBackup(context: Context, private val logger: AppLogger) {

    private val appContext = context.applicationContext

    /** Erro de restauração com mensagem para o motorista. */
    class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Monta o JSON do backup com os dados de agora. */
    fun export(appVersion: String): String {
        try {
            val prefs = JSONObject()
            BackupPolicy.PREFERENCES.forEach { name ->
                val values = JSONObject()
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    encode(value)?.let { values.put(key, it) }
                }
                prefs.put(name, values)
            }
            val files = JSONObject()
            BackupPolicy.FILES.forEach { name ->
                val file = File(appContext.filesDir, name)
                if (file.exists()) files.put(name, file.readText())
            }
            // Fotos de comprovante (já reduzidas, ~200 KB cada) em base64.
            val photos = JSONObject()
            File(appContext.filesDir, BackupPolicy.RECEIPTS_DIR).listFiles()
                ?.filter { BackupPolicy.canRestoreReceipt(it.name) }
                ?.forEach { photos.put(it.name, android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP)) }
            return JSONObject()
                .put("photos", photos)
                .put("format", BackupPolicy.FORMAT)
                .put("version", BackupPolicy.VERSION)
                .put("createdAt", LocalDateTime.now().toString())
                .put("appVersion", appVersion)
                .put("prefs", prefs)
                .put("files", files)
                .toString()
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao montar o backup", e)
            throw BackupException("Não foi possível montar o backup.", e)
        }
    }

    /**
     * Confere o arquivo escolhido e conta o que tem dentro (para o motorista confirmar).
     * @throws BackupException se não for um backup do Minha Corrida ou for de versão mais nova.
     */
    fun summarize(json: String): BackupSummary {
        val root = parse(json)
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        val rides = root.optJSONObject("files")?.optString("historico_corridas.json")
            ?.let { runCatching { JSONArray(it).length() }.getOrDefault(0) } ?: 0
        val photos = root.optJSONObject("photos")?.length() ?: 0
        return BackupSummary(
            photos = photos,
            createdAt = runCatching { LocalDateTime.parse(root.optString("createdAt")) }.getOrNull(),
            appVersion = root.optString("appVersion").takeIf { it.isNotBlank() },
            rides = rides,
            fuelEntries = countJsonArray(prefs, "minha_corrida_fuel_log"),
            trips = countJsonArray(prefs, "minha_corrida_trips"),
        )
    }

    /** Troca os dados do app pelos do backup. @throws BackupException se o arquivo for inválido. */
    fun restore(json: String) {
        val root = parse(json)
        try {
            val prefs = root.optJSONObject("prefs") ?: JSONObject()
            prefs.keys().forEach { name ->
                if (!BackupPolicy.canRestorePreference(name)) return@forEach
                val values = prefs.getJSONObject(name)
                val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                values.keys().forEach { key -> decode(editor, key, values.getJSONObject(key)) }
                editor.commit()
            }
            val files = root.optJSONObject("files") ?: JSONObject()
            files.keys().forEach { name ->
                if (!BackupPolicy.canRestoreFile(name)) return@forEach
                // Grava num temporário e renomeia: não deixa o histórico pela metade.
                val target = File(appContext.filesDir, name)
                val tmp = File(appContext.filesDir, "$name.restore")
                tmp.writeText(files.getString(name))
                if (!tmp.renameTo(target)) {
                    target.writeText(tmp.readText())
                    tmp.delete()
                }
            }
            val photos = root.optJSONObject("photos") ?: JSONObject()
            if (photos.length() > 0) {
                val dir = File(appContext.filesDir, BackupPolicy.RECEIPTS_DIR).apply { mkdirs() }
                photos.keys().forEach { name ->
                    if (!BackupPolicy.canRestoreReceipt(name)) return@forEach
                    File(dir, name).writeBytes(android.util.Base64.decode(photos.getString(name), android.util.Base64.NO_WRAP))
                }
            }
            logger.debug(TAG, "Backup restaurado")
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao restaurar o backup", e)
            throw BackupException("Não foi possível restaurar o backup.", e)
        }
    }

    private fun parse(json: String): JSONObject {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw BackupException("Este arquivo não é um backup do Minha Corrida.", e)
        }
        when (BackupPolicy.check(root.optString("format").takeIf { it.isNotEmpty() }, root.optInt("version", 0))) {
            BackupPolicy.Check.OK -> return root
            BackupPolicy.Check.NOT_A_BACKUP -> throw BackupException("Este arquivo não é um backup do Minha Corrida.")
            BackupPolicy.Check.NEWER_VERSION -> throw BackupException("Backup de uma versão mais nova do app: atualize o app antes de restaurar.")
        }
    }

    /** Abastecimentos e trajetos ficam como lista JSON dentro de uma chave das preferências. */
    private fun countJsonArray(prefs: JSONObject, name: String): Int {
        val values = prefs.optJSONObject(name) ?: return 0
        return values.keys().asSequence().sumOf { key ->
            val text = values.optJSONObject(key)?.optString("v").orEmpty()
            if (text.startsWith("[")) runCatching { JSONArray(text).length() }.getOrDefault(0) else 0
        }
    }

    private fun encode(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun decode(editor: android.content.SharedPreferences.Editor, key: String, item: JSONObject) {
        when (item.optString("t")) {
            "s" -> editor.putString(key, item.getString("v"))
            "b" -> editor.putBoolean(key, item.getBoolean("v"))
            "i" -> editor.putInt(key, item.getInt("v"))
            "l" -> editor.putLong(key, item.getLong("v"))
            "f" -> editor.putFloat(key, item.getDouble("v").toFloat())
            "set" -> {
                val array = item.getJSONArray("v")
                editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
            }
        }
    }

    private companion object {
        const val TAG = "Backup"
    }
}
