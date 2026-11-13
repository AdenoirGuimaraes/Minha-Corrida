package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.receipt.MaintenanceEntry
import br.com.minhacorrida.core.receipt.MaintenanceLogRepository
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

/** Manutenções anotadas (JSON numa chave das preferências; entra no backup). */
class SharedPrefsMaintenanceLog(context: Context, private val logger: AppLogger) : MaintenanceLogRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): List<MaintenanceEntry> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching { fromJson(array.getJSONObject(i)) }.getOrNull() }
            .sortedByDescending { it.date }
    } catch (e: Exception) {
        logger.error(TAG, "Manutenções ilegíveis", e)
        emptyList()
    }

    @Synchronized
    override fun save(entry: MaintenanceEntry) = write(all().filterNot { it.id == entry.id } + entry)

    @Synchronized
    override fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(entries: List<MaintenanceEntry>) {
        try {
            val array = JSONArray()
            entries.forEach { array.put(toJson(it)) }
            prefs.edit().putString(KEY, array.toString()).apply()
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao gravar manutenções", e)
        }
    }

    private fun toJson(e: MaintenanceEntry) = JSONObject()
        .put("id", e.id).put("date", e.date.toString()).put("d", e.description).put("v", e.value.toPlainString())

    private fun fromJson(o: JSONObject) = MaintenanceEntry(
        id = o.getString("id"),
        date = LocalDate.parse(o.getString("date")),
        description = o.getString("d"),
        value = BigDecimal(o.getString("v")),
    )

    private companion object {
        const val TAG = "MaintenanceLog"
        const val PREFS_NAME = "minha_corrida_maintenance_log"
        const val KEY = "entries"
    }
}
