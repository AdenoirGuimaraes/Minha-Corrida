package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.km.FuelEntry
import br.com.minhacorrida.core.km.FuelLogRepository
import br.com.minhacorrida.core.km.KmLogRepository
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Km do dia somados pelo GPS: uma chave por dia ("2026-11-10" → metros).
 * Dias com mais de [RETENTION_DAYS] são apagados (como o histórico de corridas).
 */
class SharedPrefsKmLogRepository(context: Context, private val logger: AppLogger) : KmLogRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun addMeters(date: LocalDate, meters: Double) {
        if (meters <= 0) return
        try {
            val key = date.toString()
            val total = (prefs.getString(key, null)?.toDoubleOrNull() ?: 0.0) + meters
            prefs.edit().putString(key, total.toString()).apply()
            purgeOld(date)
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao somar km do dia", e)
        }
    }

    override fun kmBetween(start: LocalDate, endExclusive: LocalDate): BigDecimal = try {
        var meters = 0.0
        var day = start
        while (day < endExclusive) {
            meters += prefs.getString(day.toString(), null)?.toDoubleOrNull() ?: 0.0
            day = day.plusDays(1)
        }
        BigDecimal(meters).divide(BigDecimal(1000), 1, RoundingMode.HALF_UP)
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao ler km do período", e)
        BigDecimal.ZERO
    }

    private fun purgeOld(today: LocalDate) {
        val limit = today.minusDays(RETENTION_DAYS)
        val old = prefs.all.keys.filter { key -> runCatching { LocalDate.parse(key) < limit }.getOrDefault(false) }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach(::remove) }.apply()
    }

    private companion object {
        const val TAG = "KmLog"
        const val PREFS_NAME = "minha_corrida_km_log"
        const val RETENTION_DAYS = 60L
    }
}

/** Abastecimentos anotados (JSON numa chave das preferências; poucas dezenas por mês). */
class SharedPrefsFuelLogRepository(context: Context, private val logger: AppLogger) : FuelLogRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): List<FuelEntry> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching { fromJson(array.getJSONObject(i)) }.getOrNull() }
            .sortedWith(compareByDescending<FuelEntry> { it.date }.thenByDescending { it.odometerKm })
    } catch (e: Exception) {
        logger.error(TAG, "Abastecimentos ilegíveis", e)
        emptyList()
    }

    @Synchronized
    override fun save(entry: FuelEntry) = write(all().filterNot { it.id == entry.id } + entry)

    @Synchronized
    override fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(entries: List<FuelEntry>) {
        try {
            val array = JSONArray()
            entries.forEach { array.put(toJson(it)) }
            prefs.edit().putString(KEY, array.toString()).apply()
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao gravar abastecimentos", e)
        }
    }

    private fun toJson(e: FuelEntry) = JSONObject()
        .put("id", e.id)
        .put("date", e.date.toString())
        .put("odo", e.odometerKm.toPlainString())
        .put("l", e.liters.toPlainString())
        .put("p", e.totalPrice.toPlainString())
        .put("full", e.fullTank)

    private fun fromJson(o: JSONObject) = FuelEntry(
        id = o.getString("id"),
        date = LocalDate.parse(o.getString("date")),
        odometerKm = BigDecimal(o.getString("odo")),
        liters = BigDecimal(o.getString("l")),
        totalPrice = BigDecimal(o.getString("p")),
        fullTank = o.optBoolean("full", true),
    )

    private companion object {
        const val TAG = "FuelLog"
        const val PREFS_NAME = "minha_corrida_fuel_log"
        const val KEY = "entries"
    }
}
