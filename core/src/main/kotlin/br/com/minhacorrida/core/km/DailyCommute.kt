package br.com.minhacorrida.core.km

import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Check "Descontar o deslocamento deste dia" da aba Corridas: por dia, o motorista escolhe se a
 * gasolina do deslocamento sem passageiro entra no Separar e sai do "Fica com você".
 * Desmarcado (padrão): só a gasolina das corridas, como antes; o deslocamento aparece só para saber.
 */
interface CommuteChoiceRepository {
    fun isIncluded(date: LocalDate): Boolean
    fun setIncluded(date: LocalDate, included: Boolean)
}

/**
 * Deslocamento de um período, separado pelos checks de cada dia.
 * @property included soma dos dias marcados (entra nas contas); null se nenhum.
 * @property excluded soma dos dias desmarcados (só informativo); null se nenhum.
 * @property days deslocamento de cada dia com corrida realizada e se está marcado.
 */
data class PeriodCommute(
    val included: CommuteCost?,
    val excluded: CommuteCost?,
    val days: Map<LocalDate, Pair<CommuteCost, Boolean>>,
)

/**
 * Deslocamento por dia (só dias com corrida realizada), igual para a aba Corridas e o relatório:
 * km do GPS do dia × ajuste do painel − km das corridas; sem GPS no dia, média do painel × 1 dia.
 */
class DailyCommuteUseCase(
    private val settings: SettingsRepository,
    private val kmSettings: KmSettingsRepository,
    private val kmLog: KmLogRepository,
    private val fuelLog: FuelLogRepository,
    private val choices: CommuteChoiceRepository,
    private val logger: AppLogger,
) {
    /**
     * @param ridesKmByDay km das corridas REALIZADAS de cada dia do período (só dias com corrida).
     * @param doneKmBetween km das corridas realizadas num intervalo (para a média do painel).
     */
    fun forPeriod(
        ridesKmByDay: Map<LocalDate, BigDecimal>,
        doneKmBetween: (LocalDate, LocalDate) -> BigDecimal,
    ): PeriodCommute = try {
        if (!kmSettings.isKmTrackingEnabled() || ridesKmByDay.isEmpty()) {
            PeriodCommute(null, null, emptyMap())
        } else {
            val cost = settings.getOperatingCost()
            val stats = FuelStatsCalculator.of(fuelLog.all())
            val from = stats.from
            val to = stats.to
            val correction = if (from != null && to != null) KmCorrection.factor(stats, kmLog.kmBetween(from, to)) else null
            val averagePerDay = if (from != null && to != null) OdometerCommute.kmPerDay(stats, doneKmBetween(from, to)) else null

            val days = ridesKmByDay.mapNotNull { (day, ridesKm) ->
                val gps = kmLog.kmOn(day)
                val commute = if (gps.signum() > 0) {
                    CommuteCost.of(gps, ridesKm, cost?.fuelPricePerLiter, cost?.kmPerLiter, correction)
                } else {
                    CommuteCost.averaged(averagePerDay, 1, cost?.fuelPricePerLiter, cost?.kmPerLiter)
                }
                commute?.let { day to (it to choices.isIncluded(day)) }
            }.toMap()
            PeriodCommute(
                included = sum(days.values.filter { it.second }.map { it.first }),
                excluded = sum(days.values.filterNot { it.second }.map { it.first }),
                days = days,
            )
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao calcular o deslocamento", e)
        PeriodCommute(null, null, emptyMap())
    }

    companion object {
        private const val TAG = "DailyCommute"

        /** Soma os deslocamentos de vários dias; null se a lista for vazia. */
        fun sum(list: List<CommuteCost>): CommuteCost? {
            if (list.isEmpty()) return null
            if (list.size == 1) return list.single()
            fun total(f: (CommuteCost) -> BigDecimal) = list.fold(BigDecimal.ZERO) { acc, c -> acc + f(c) }
            // Média do painel em todos os dias somados: mantém o km/dia para o texto.
            val average = list.mapNotNull { it.averagePerDay }.takeIf { it.size == list.size }?.first()
            return CommuteCost(
                totalKm = total { it.totalKm }.setScale(1, RoundingMode.HALF_UP),
                ridesKm = total { it.ridesKm }.setScale(1, RoundingMode.HALF_UP),
                commuteKm = total { it.commuteKm }.setScale(1, RoundingMode.HALF_UP),
                fuelCost = total { it.fuelCost }.setScale(2, RoundingMode.HALF_UP),
                averagePerDay = average,
                correction = list.firstNotNullOfOrNull { it.correction },
            )
        }
    }
}
