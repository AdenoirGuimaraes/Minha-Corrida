package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.market.OfferLogRepository
import br.com.minhacorrida.core.market.OfferMarket
import br.com.minhacorrida.core.market.OfferSample
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.Executors

/**
 * Ofertas lidas num arquivo próprio ("ofertas.jsonl", uma linha por oferta, ~150 bytes),
 * separado do histórico de corridas e fora do backup. Gravação em segundo plano (não segura a
 * leitura da tela); linhas com mais de [OfferMarket.RETENTION_DAYS] dias são apagadas uma vez por dia.
 */
class JsonLinesOfferLogRepository(context: Context, private val logger: AppLogger) : OfferLogRepository {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val writer = Executors.newSingleThreadExecutor()
    private var lastPurgeDay = -1L

    override fun add(sample: OfferSample) {
        writer.execute {
            try {
                synchronized(this) {
                    file.appendText(toJson(sample).toString() + "\n")
                    val day = sample.atMillis / DAY_MS
                    if (day != lastPurgeDay) {
                        lastPurgeDay = day
                        purge(sample.atMillis - OfferMarket.RETENTION_DAYS * DAY_MS)
                    }
                }
            } catch (e: Exception) {
                logger.error(TAG, "Falha ao gravar oferta", e)
            }
        }
    }

    override fun since(fromMillis: Long): List<OfferSample> = try {
        synchronized(this) {
            if (!file.exists()) emptyList() else file.readLines().mapNotNull { parse(it) }.filter { it.atMillis >= fromMillis }
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao ler ofertas", e)
        emptyList()
    }

    /** Regrava só as ofertas recentes (arquivo temporário + renomear). */
    private fun purge(olderThanMillis: Long) {
        if (!file.exists()) return
        val lines = file.readLines()
        val kept = lines.filter { line -> parse(line)?.let { it.atMillis >= olderThanMillis } ?: false }
        if (kept.size == lines.size) return
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(file)) file.writeText(tmp.readText())
    }

    private fun toJson(s: OfferSample) = JSONObject()
        .put("t", s.atMillis).put("p", s.platform.name).put("f", s.fare.toPlainString())
        .put("k", s.km.toPlainString()).put("v", s.netPerKm.toPlainString())
        .put("l", s.profit?.toPlainString() ?: JSONObject.NULL).put("m", s.minutes ?: JSONObject.NULL)

    private fun parse(line: String): OfferSample? = runCatching {
        if (line.isBlank()) return null
        val o = JSONObject(line)
        OfferSample(
            atMillis = o.getLong("t"),
            platform = Platform.valueOf(o.getString("p")),
            fare = BigDecimal(o.getString("f")),
            km = BigDecimal(o.getString("k")),
            netPerKm = BigDecimal(o.getString("v")),
            profit = if (o.isNull("l")) null else BigDecimal(o.getString("l")),
            minutes = if (o.isNull("m")) null else o.getInt("m"),
        )
    }.getOrNull()

    private companion object {
        const val TAG = "OfferLog"
        const val FILE_NAME = "ofertas.jsonl"
        const val DAY_MS = 24 * 3_600_000L
    }
}
