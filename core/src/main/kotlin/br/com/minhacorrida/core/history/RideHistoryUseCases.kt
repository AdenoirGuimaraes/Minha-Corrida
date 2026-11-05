package br.com.minhacorrida.core.history

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Guarda no histórico as corridas mostradas no card (uma vez por corrida):
 * - Uber: só as VERDES;
 * - inDrive: TODOS os pedidos abertos em tela cheia, verdes e vermelhos — o motorista pode
 *   fazer contraproposta e o passageiro aceitar um valor maior (a vermelha vira boa).
 * A mesma corrida lida de novo em poucos minutos (eventos repetidos da tela) não duplica.
 */
class RecordRideUseCase(
    private val repository: RideHistoryRepository,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** @return true se a corrida foi guardada agora. */
    operator fun invoke(analysis: RideAnalysis): Boolean {
        return try {
            val offer = analysis.offer
            val good = analysis.rating == RideRating.GOOD
            if (!good && offer.platform != Platform.INDRIVE) return false
            val now = nowMillis()
            val recent = repository.between(now - DUPLICATE_WINDOW_MS, now + 1)
            if (recent.any { it.platform == offer.platform && it.fare.compareTo(offer.fare) == 0 &&
                    it.totalKm.compareTo(analysis.metrics.totalKm) == 0 }
            ) return false

            val totalKm = analysis.metrics.totalKm
            val cost = settings.getOperatingCost()
            val fuel = cost?.let { (totalKm * it.fuelPricePerLiter).divide(it.kmPerLiter, 2, RoundingMode.HALF_UP) }
            val maintenance = cost?.let { (totalKm * it.otherCostPerKm).setScale(2, RoundingMode.HALF_UP) }
            repository.add(
                RideRecord(
                    id = "$now-${offer.platform.name}-${offer.fare.toPlainString()}",
                    timestampMillis = now,
                    platform = offer.platform,
                    fare = offer.fare.setScale(2, RoundingMode.HALF_UP),
                    pickupKm = offer.pickupKm,
                    tripKm = offer.tripKm,
                    totalKm = totalKm,
                    valuePerKm = analysis.metrics.valuePerKm,
                    fuelCost = fuel,
                    maintenanceCost = maintenance,
                    good = good,
                ),
            )
            true
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao guardar corrida no histórico", e)
            false
        }
    }

    private companion object {
        const val TAG = "RecordRide"
        const val DUPLICATE_WINDOW_MS = 10 * 60 * 1000L
    }
}

/**
 * Soma das corridas REALIZADAS (marcadas) de um período.
 *
 * @property ridesWithoutCost corridas guardadas antes de o motorista informar os custos
 *   (entram no km e no valor, mas sem gasto).
 */
data class RideHistorySummary(
    val doneCount: Int,
    val totalKm: BigDecimal,
    val totalFare: BigDecimal,
    val fuelCost: BigDecimal,
    val maintenanceCost: BigDecimal,
    val netProfit: BigDecimal,
    val ridesWithoutCost: Int,
) {
    /** Valor a separar para abastecer e manter o carro. */
    val totalCost: BigDecimal get() = fuelCost + maintenanceCost
}

/** Uma tela do histórico: o intervalo, todas as corridas verdes nele e a soma das realizadas. */
data class RideHistoryPage(
    val period: HistoryPeriod,
    val range: DateRange,
    val rides: List<RideRecord>,
    val summary: RideHistorySummary,
)

/**
 * Consulta por dia/semana/mês, marcação de "realizada", troca de valor (contraproposta e
 * correção à mão) e limpeza das corridas com mais de 60 dias.
 */
class RideHistoryUseCase(
    private val repository: RideHistoryRepository,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun page(period: HistoryPeriod, reference: LocalDate): RideHistoryPage {
        val range = period.rangeOf(reference)
        val rides = try {
            repository.between(range.startMillis(zone), range.endMillis(zone))
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao ler o histórico", e)
            emptyList()
        }
        return RideHistoryPage(period, range, rides, summarize(rides))
    }

    fun setDone(id: String, done: Boolean) {
        try {
            repository.setDone(id, done)
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao marcar corrida", e)
        }
    }

    /**
     * Corrige o valor de uma corrida (toque longo na aba Corridas).
     * @return a corrida atualizada, ou null se o valor for inválido ou a corrida não existir.
     */
    fun changeFare(id: String, newFare: BigDecimal): RideRecord? = try {
        val record = repository.findById(id)
        if (record == null || newFare.signum() <= 0) {
            null
        } else {
            record.withFare(newFare, settings.getMinValuePerKm()).also(repository::replace)
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao corrigir o valor", e)
        null
    }

    /**
     * Toque num botão de valor do pedido do inDrive ("R$ 17,50", "Aceitar por R$ 15"):
     * aplica o valor ao pedido do inDrive guardado mais recentemente (últimos minutos).
     * Textos que não são um valor ("Ofereça sua tarifa", "Fechar") são ignorados.
     * @return a corrida atualizada, ou null se nada mudou.
     */
    fun applyInDriveFareTap(buttonText: String): RideRecord? = try {
        val fare = COUNTER_OFFER.matchEntire(buttonText.trim())?.groupValues?.get(1)?.let(NumberParser::parse)
        if (fare == null || fare.signum() <= 0) {
            null
        } else {
            val now = nowMillis()
            val latest = repository.between(now - COUNTER_OFFER_WINDOW_MS, now + 1)
                .firstOrNull { it.platform == Platform.INDRIVE }
            if (latest == null || latest.fare.compareTo(fare) == 0) {
                null
            } else {
                latest.withFare(fare, settings.getMinValuePerKm()).also(repository::replace)
            }
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao aplicar contraproposta", e)
        null
    }

    /** Apaga o que for mais antigo que [RETENTION_DAYS] dias. */
    fun purgeOld(): Int = try {
        repository.deleteOlderThan(today().minusDays(RETENTION_DAYS).atStartOfDay(zone).toInstant().toEpochMilli())
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao limpar o histórico", e)
        0
    }

    companion object {
        private const val TAG = "RideHistory"
        const val RETENTION_DAYS = 60L

        /** Pedido do inDrive fica "aberto" para contraproposta por até 5 minutos. */
        private const val COUNTER_OFFER_WINDOW_MS = 5 * 60 * 1000L

        /** "R$ 17,50" ou "Aceitar por R$ 15" (botões de valor do pedido do inDrive). */
        private val COUNTER_OFFER = Regex("""(?:Aceitar\s+por\s+)?R\$\s*(\d+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)

        /** Soma só as corridas marcadas como realizadas. */
        fun summarize(rides: List<RideRecord>): RideHistorySummary {
            val done = rides.filter { it.done }
            fun sum(values: List<BigDecimal>) = values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
            return RideHistorySummary(
                doneCount = done.size,
                totalKm = sum(done.map { it.totalKm }),
                totalFare = sum(done.map { it.fare }),
                fuelCost = sum(done.mapNotNull { it.fuelCost }),
                maintenanceCost = sum(done.mapNotNull { it.maintenanceCost }),
                netProfit = sum(done.map { it.netProfit }),
                ridesWithoutCost = done.count { it.totalCost == null },
            )
        }
    }
}
