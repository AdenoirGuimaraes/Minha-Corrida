package br.com.minhacorrida.core.history

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Guarda uma corrida no histórico (uma vez por corrida). Quem chama decide quando:
 * Uber quando o motorista toca em "Aceitar" ou "Tenho interesse" (ver [br.com.minhacorrida.core.usecase.UberTapUseCase]);
 * inDrive quando toca em "Aceitar por" ou numa contraproposta (ver [br.com.minhacorrida.core.usecase.InDriveTapUseCase]).
 * A mesma corrida lida de novo em poucos minutos (eventos repetidos da tela) não duplica.
 */
class RecordRideUseCase(
    private val repository: RideHistoryRepository,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param originalFare valor oferecido pelo passageiro, quando o valor guardado é de contraproposta.
     * @param acceptedByDriver true quando o motorista ACEITOU (toque no botão): guarda qualquer cor,
     *   inclusive vermelha — foi decisão dele. false: só verde ou amarela.
     * @return a corrida guardada agora, ou null (vermelha sem aceite, repetida ou erro).
     */
    operator fun invoke(
        analysis: RideAnalysis,
        originalFare: BigDecimal? = null,
        acceptedByDriver: Boolean = false,
    ): RideRecord? {
        return try {
            val offer = analysis.offer
            // Sem aceite do motorista, só verde ou amarelo (R$/km ok) entram.
            if (!acceptedByDriver && !analysis.rating.isAcceptable) return null
            val now = nowMillis()
            val recent = repository.between(now - DUPLICATE_WINDOW_MS, now + 1)
            if (recent.any { it.platform == offer.platform && it.fare.compareTo(offer.fare) == 0 &&
                    it.totalKm.compareTo(analysis.metrics.totalKm) == 0 }
            ) return null

            val totalKm = analysis.metrics.totalKm
            val (fuel, maintenance) = RideCosts.of(totalKm, settings.getOperatingCost())
            val saved = RideRecord(
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
                    good = analysis.rating.isAcceptable,
                    lowProfit = analysis.rating == RideRating.LOW_PROFIT,
                    feePercent = analysis.metrics.feePercent,
                    originalFare = originalFare
                        ?.setScale(2, RoundingMode.HALF_UP)
                        ?.takeIf { it.compareTo(offer.fare) != 0 },
                )
            repository.add(saved)
            saved
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao guardar corrida no histórico", e)
            null
        }
    }

    private companion object {
        const val TAG = "RecordRide"
        const val DUPLICATE_WINDOW_MS = 10 * 60 * 1000L
    }
}

/** Gasto de uma corrida pelo km total: combustível e manutenção (null se os custos não foram informados). */
internal object RideCosts {
    fun of(totalKm: BigDecimal, cost: br.com.minhacorrida.core.model.OperatingCost?): Pair<BigDecimal?, BigDecimal?> {
        if (cost == null) return null to null
        val fuel = (totalKm * cost.fuelPricePerLiter).divide(cost.kmPerLiter, 2, RoundingMode.HALF_UP)
        val maintenance = (totalKm * cost.otherCostPerKm).setScale(2, RoundingMode.HALF_UP)
        return fuel to maintenance
    }
}

/**
 * Soma das corridas REALIZADAS (marcadas) de um período.
 *
 * @property totalFare o que ficou com o motorista (valores já sem a taxa do inDrive).
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
            record.withFare(newFare, settings.getMinValuePerKm(), settings.getMinNetProfit()).also(repository::replace)
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao corrigir o valor", e)
        null
    }

    /**
     * Botão "Corridas Selecionadas": apaga as corridas NÃO marcadas do período na tela;
     * as marcadas (realizadas) ficam. @return quantas foram apagadas.
     */
    fun keepOnlyDone(period: HistoryPeriod, reference: LocalDate): Int = try {
        val toDelete = page(period, reference).rides.filterNot { it.done }
        toDelete.forEach { repository.delete(it.id) }
        toDelete.size
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao apagar corridas não marcadas", e)
        0
    }

    /**
     * Botão "Registrar corrida": corrida que o leitor não gravou (ou particular), já marcada
     * como realizada. Gastos com os custos do carro de agora; cor pelas regras de agora.
     * No inDrive, [fare] é o valor do app e a taxa configurada é descontada (como no leitor);
     * Uber (o valor já é o ganho do motorista) e Particular não têm desconto.
     *
     * @param pickupKm km até o passageiro (null/zero se saiu do lugar dele).
     * @param platform app da corrida (padrão: Particular).
     * @return a corrida guardada, ou null se os valores forem inválidos.
     */
    fun registerManual(
        fare: BigDecimal,
        pickupKm: BigDecimal?,
        tripKm: BigDecimal,
        timestampMillis: Long,
        platform: Platform = Platform.PARTICULAR,
    ): RideRecord? = try {
        if (fare.signum() <= 0 || tripKm.signum() <= 0 || (pickupKm != null && pickupKm.signum() < 0)) {
            null
        } else {
            val totalKm = (tripKm + (pickupKm ?: BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP)
            val roundedFare = fare.setScale(2, RoundingMode.HALF_UP)
            val feePercent = if (platform == Platform.INDRIVE) settings.getInDriveFeePercent() else BigDecimal.ZERO
            val net = br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase.netOf(roundedFare, feePercent)
            val perKm = net.divide(totalKm, 2, RoundingMode.HALF_UP)
            val (fuel, maintenance) = RideCosts.of(totalKm, settings.getOperatingCost())
            RideRecord(
                id = "$timestampMillis-${platform.name}-${roundedFare.toPlainString()}",
                timestampMillis = timestampMillis,
                platform = platform,
                fare = roundedFare,
                pickupKm = pickupKm,
                tripKm = tripKm,
                totalKm = totalKm,
                valuePerKm = perKm,
                fuelCost = fuel,
                maintenanceCost = maintenance,
                done = true,
                good = perKm >= settings.getMinValuePerKm(),
                feePercent = feePercent,
            ).let { r ->
                val minProfit = settings.getMinNetProfit()
                r.copy(lowProfit = r.good && minProfit != null && r.totalCost != null && r.netProfit < minProfit)
            }.also(repository::add)
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao registrar corrida manual", e)
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

        /** Soma só as corridas marcadas como realizadas. */
        fun summarize(rides: List<RideRecord>): RideHistorySummary {
            val done = rides.filter { it.done }
            fun sum(values: List<BigDecimal>) = values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
            return RideHistorySummary(
                doneCount = done.size,
                totalKm = sum(done.map { it.totalKm }),
                totalFare = sum(done.map { it.netFare }),
                fuelCost = sum(done.mapNotNull { it.fuelCost }),
                maintenanceCost = sum(done.mapNotNull { it.maintenanceCost }),
                netProfit = sum(done.map { it.netProfit }),
                ridesWithoutCost = done.count { it.totalCost == null },
            )
        }
    }
}
