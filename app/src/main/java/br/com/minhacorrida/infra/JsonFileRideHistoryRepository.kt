package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.history.RideHistoryRepository
import br.com.minhacorrida.core.history.RideRecord
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal

/**
 * Histórico de corridas num arquivo JSON do próprio app (máx. 60 dias, poucos KB por dia).
 * Fica em memória depois da 1ª leitura; cada mudança regrava o arquivo inteiro de forma
 * atômica (arquivo temporário + renomear), para não corromper se o app fechar no meio.
 * Uma única instância (AppContainer) é compartilhada pelo serviço e pela tela.
 */
class JsonFileRideHistoryRepository(context: Context, private val logger: AppLogger) : RideHistoryRepository {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()
    private var cache: MutableList<RideRecord>? = null

    override fun add(record: RideRecord) = synchronized(lock) {
        records().add(record)
        save()
    }

    override fun between(fromMillis: Long, toMillis: Long): List<RideRecord> = synchronized(lock) {
        records().filter { it.timestampMillis in fromMillis until toMillis }.sortedByDescending { it.timestampMillis }
    }

    override fun setDone(id: String, done: Boolean) = synchronized(lock) {
        val list = records()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0 && list[i].done != done) {
            list[i] = list[i].copy(done = done)
            save()
        }
    }

    override fun findById(id: String): RideRecord? = synchronized(lock) { records().firstOrNull { it.id == id } }

    override fun replace(record: RideRecord) = synchronized(lock) {
        val list = records()
        val i = list.indexOfFirst { it.id == record.id }
        if (i >= 0) {
            list[i] = record
            save()
        }
    }

    override fun delete(id: String) = synchronized(lock) {
        if (records().removeAll { it.id == id }) save()
    }

    override fun deleteOlderThan(cutoffMillis: Long): Int = synchronized(lock) {
        val list = records()
        val before = list.size
        list.removeAll { it.timestampMillis < cutoffMillis }
        val removed = before - list.size
        if (removed > 0) save()
        removed
    }

    private fun records(): MutableList<RideRecord> = cache ?: load().also { cache = it }

    private fun load(): MutableList<RideRecord> {
        if (!file.exists()) return mutableListOf()
        return try {
            val array = JSONArray(file.readText())
            MutableList(array.length()) { fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            logger.error(TAG, "Histórico corrompido; começando vazio", e)
            mutableListOf()
        }
    }

    private fun save() {
        try {
            val array = JSONArray()
            records().forEach { array.put(toJson(it)) }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao gravar o histórico", e)
        }
    }

    private fun toJson(r: RideRecord) = JSONObject().apply {
        put("id", r.id)
        put("ts", r.timestampMillis)
        put("platform", r.platform.name)
        put("fare", r.fare.toPlainString())
        r.pickupKm?.let { put("pickupKm", it.toPlainString()) }
        put("tripKm", r.tripKm.toPlainString())
        put("totalKm", r.totalKm.toPlainString())
        put("valuePerKm", r.valuePerKm.toPlainString())
        r.fuelCost?.let { put("fuel", it.toPlainString()) }
        r.maintenanceCost?.let { put("maintenance", it.toPlainString()) }
        put("done", r.done)
        put("good", r.good)
        if (r.lowProfit) put("lowProfit", true)
        r.originalFare?.let { put("originalFare", it.toPlainString()) }
        if (r.feePercent.signum() > 0) put("feePercent", r.feePercent.toPlainString())
        r.minutes?.let { put("minutes", it) }
        if (r.minutesEstimated) put("minutesEst", true)
    }

    private fun fromJson(o: JSONObject) = RideRecord(
        id = o.getString("id"),
        timestampMillis = o.getLong("ts"),
        platform = Platform.valueOf(o.getString("platform")),
        fare = BigDecimal(o.getString("fare")),
        pickupKm = o.optString("pickupKm").takeIf { it.isNotEmpty() }?.let(::BigDecimal),
        tripKm = BigDecimal(o.getString("tripKm")),
        totalKm = BigDecimal(o.getString("totalKm")),
        valuePerKm = BigDecimal(o.getString("valuePerKm")),
        fuelCost = o.optString("fuel").takeIf { it.isNotEmpty() }?.let(::BigDecimal),
        maintenanceCost = o.optString("maintenance").takeIf { it.isNotEmpty() }?.let(::BigDecimal),
        done = o.optBoolean("done", false),
        good = o.optBoolean("good", true),
        lowProfit = o.optBoolean("lowProfit", false),
        originalFare = o.optString("originalFare").takeIf { it.isNotEmpty() }?.let(::BigDecimal),
        feePercent = o.optString("feePercent").takeIf { it.isNotEmpty() }?.let(::BigDecimal) ?: BigDecimal.ZERO,
        minutes = if (o.has("minutes")) o.optInt("minutes") else null,
        minutesEstimated = o.optBoolean("minutesEst", false),
    )

    private companion object {
        const val TAG = "RideHistoryFile"
        const val FILE_NAME = "historico_corridas.json"
    }
}
