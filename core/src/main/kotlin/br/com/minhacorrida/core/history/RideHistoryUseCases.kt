package br.com.minhacorrida.core.history

import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Guarda no histórico cada corrida VERDE mostrada no card (uma vez por corrida).
 * A mesma corrida lida de novo em poucos minutos (eventos repetidos da tela) não duplica.
 */
class RecordGoodRideUseCase(
    private val repository: RideHistoryRepository,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** @return true se a corrida foi guardada agora. */
    operator fun invoke(analysis: RideAnalysis): Boolean {
        return try {
            if (analysis.rating != RideRating.GOOD) return false
            val offer = analysis.offer
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
                ),
            )
            true
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao guardar corrida no histórico", e)
            false
        }
    }

    private companion object {
        const val TAG = "RecordGoodRide"
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

/** Consulta por dia/semana/mês, marcação de "realizada" e limpeza das corridas com mais de 60 dias. */
class RideHistoryUseCase(
    private val repository: RideHistoryRepository,
    private val logger: AppLogger,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
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
