package br.com.minhacorrida.core.market

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.AppLogger
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Uma oferta lida (aceita ou não), só com números — para saber se os mínimos estão de acordo
 * com o que Uber e inDrive pagam no município. Fica num arquivo próprio, separado do histórico
 * de corridas, e é apagada depois de [OfferMarket.RETENTION_DAYS] dias.
 *
 * @property netPerKm R$/km líquido (depois da taxa do inDrive).
 * @property profit lucro líquido da oferta; null sem custos configurados.
 */
data class OfferSample(
    val atMillis: Long,
    val platform: Platform,
    val fare: BigDecimal,
    val km: BigDecimal,
    val netPerKm: BigDecimal,
    val profit: BigDecimal?,
    val minutes: Int?,
)

interface OfferLogRepository {
    fun add(sample: OfferSample)

    /** Ofertas lidas a partir de [fromMillis]. */
    fun since(fromMillis: Long): List<OfferSample>
}

/**
 * Registra cada oferta UMA vez: a tela é lida várias vezes por segundo e a mesma oferta
 * aparece de novo (ou volta depois de fechar); só conta se for outra oferta ou já passou
 * [REPEAT_MS] desde a última vez que aquela oferta foi contada.
 */
class OfferLogUseCase(private val repository: OfferLogRepository, private val logger: AppLogger) {
    private var lastOffer: RideOffer? = null
    private var lastAt = 0L

    /** @return true se a oferta foi registrada agora. */
    @Synchronized
    fun seen(analysis: RideAnalysis, nowMillis: Long): Boolean = try {
        val offer = analysis.offer
        if (offer.platform == Platform.PARTICULAR ||
            (offer.sameRideAs(lastOffer) && nowMillis - lastAt < REPEAT_MS)
        ) {
            false
        } else {
            lastOffer = offer
            lastAt = nowMillis
            repository.add(
                OfferSample(
                    atMillis = nowMillis,
                    platform = offer.platform,
                    fare = offer.fare,
                    km = analysis.metrics.totalKm,
                    netPerKm = analysis.metrics.valuePerKm,
                    profit = analysis.profit?.netProfit,
                    minutes = analysis.time.minutes,
                ),
            )
            true
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao registrar a oferta", e)
        false
    }

    companion object {
        private const val TAG = "OfferLog"
        const val REPEAT_MS = 10 * 60_000L
    }
}

/**
 * "Seus mínimos estão de acordo com o município?" — compara os mínimos ATUAIS com as ofertas lidas.
 */
object OfferMarket {
    const val RETENTION_DAYS = 60L

    /** Abaixo disso o resultado ainda não diz muito. */
    const val MIN_OFFERS = 20

    data class Check(
        val offers: Int,
        val byPlatform: Map<Platform, Int>,
        val medianPerKm: BigDecimal,
        val topQuarterPerKm: BigDecimal,
        /** % das ofertas que atingem o R$/km mínimo. */
        val reachPerKm: Int,
        /** % que atingem o lucro mínimo (null sem lucro mínimo ou sem custos). */
        val reachProfit: Int?,
        /** % que atingem os dois (seriam verdes, sem contar nota e R$/min). */
        val reachBoth: Int,
    )

    fun check(samples: List<OfferSample>, minPerKm: BigDecimal, minProfit: BigDecimal?): Check? {
        if (samples.isEmpty()) return null
        val perKm = samples.map { it.netPerKm }.sorted()
        val withProfit = samples.filter { it.profit != null }
        val profitRule = minProfit?.takeIf { withProfit.isNotEmpty() }
        fun okKm(s: OfferSample) = s.netPerKm >= minPerKm
        fun okProfit(s: OfferSample) = profitRule == null || (s.profit != null && s.profit >= profitRule)
        return Check(
            offers = samples.size,
            byPlatform = samples.groupingBy { it.platform }.eachCount(),
            medianPerKm = percentile(perKm, 50),
            topQuarterPerKm = percentile(perKm, 75),
            reachPerKm = percent(samples.count(::okKm), samples.size),
            reachProfit = profitRule?.let { rule -> percent(withProfit.count { it.profit!! >= rule }, withProfit.size) },
            reachBoth = percent(samples.count { okKm(it) && okProfit(it) }, samples.size),
        )
    }

    /** Texto da seção "Seus mínimos × ofertas de Lages" na janela Análises. */
    fun text(check: Check?, minPerKm: BigDecimal, minProfit: BigDecimal?, periodLabel: String): String {
        if (check == null) return "📍 Seus mínimos × ofertas\nNenhuma oferta lida em $periodLabel ainda."
        val platforms = check.byPlatform.entries.sortedBy { it.key.ordinal }
            .joinToString(" · ") { "${it.key.displayName} ${it.value}" }
        val lines = mutableListOf(
            "📍 Seus mínimos × ofertas ($periodLabel)",
            "${check.offers} oferta(s) lidas ($platforms)",
            "Metade das ofertas paga até R$ ${money(check.medianPerKm)}/km; 1 em cada 4 paga R$ ${money(check.topQuarterPerKm)}/km ou mais.",
            "Seu mínimo R$ ${money(minPerKm)}/km: ${check.reachPerKm}% das ofertas atingem.",
        )
        if (check.reachProfit != null && minProfit != null) {
            lines += "Seu lucro mínimo R$ ${money(minProfit)}: ${check.reachProfit}% atingem."
        }
        lines += "Passam nos dois: ${check.reachBoth}%."
        lines += verdict(check)
        return lines.joinToString("\n")
    }

    /** Leitura simples do resultado. */
    fun verdict(check: Check): String = when {
        check.offers < MIN_OFFERS -> "👉 Ainda poucas ofertas lidas ($MIN_OFFERS ou mais para uma leitura confiável)."
        check.reachBoth < 15 -> "👉 Mínimos ALTOS para o município: poucas ofertas passam. Pode estar recusando demais nos horários fracos."
        check.reachBoth > 60 -> "👉 Mínimos FOLGADOS: a maioria das ofertas passa. Dá para subir um pouco e escolher melhor."
        else -> "👉 Mínimos EQUILIBRADOS com o que o município paga."
    }

    /** Percentil por posição (lista já ordenada). */
    private fun percentile(sorted: List<BigDecimal>, p: Int): BigDecimal {
        val index = ((sorted.size - 1) * p / 100.0).let { Math.round(it).toInt() }.coerceIn(0, sorted.lastIndex)
        return sorted[index].setScale(2, RoundingMode.HALF_UP)
    }

    private fun percent(part: Int, total: Int) = if (total == 0) 0 else Math.round(part * 100.0 / total).toInt()
    private fun money(v: BigDecimal) = v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
