package br.com.minhacorrida.core.goal

import br.com.minhacorrida.core.history.HistoryPeriod
import br.com.minhacorrida.core.history.RideHistoryUseCase
import br.com.minhacorrida.core.km.DailyCommuteUseCase
import br.com.minhacorrida.core.port.AppLogger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Frase falada depois de cada corrida concluída (pedido de 15/11), com o mesmo "Fica com você"
 * da aba Corridas: "Faltam 23 reais e 40 centavos para a meta do dia." /
 * "Meta do dia batida! Passou 3 reais e 30 centavos." Valores por extenso para a voz ler certo.
 */
object GoalAnnouncement {

    /** @return a frase, ou null se não há meta configurada. */
    fun text(progress: GoalProgress): String? {
        progress.goal ?: return null
        return if (progress.reached) {
            val surplus = progress.surplus
            if (surplus.signum() > 0) "Meta do dia batida! Passou ${spoken(surplus)}." else "Meta do dia batida!"
        } else {
            "Faltam ${spoken(progress.remaining ?: BigDecimal.ZERO)} para a meta do dia."
        }
    }

    /** 23.40 → "23 reais e 40 centavos"; 1.00 → "1 real"; 0.50 → "50 centavos". */
    fun spoken(value: BigDecimal): String {
        val v = value.abs().setScale(2, RoundingMode.HALF_UP)
        val reais = v.toBigInteger().toInt()
        val centavos = v.remainder(BigDecimal.ONE).movePointRight(2).toInt()
        val r = when (reais) { 0 -> null; 1 -> "1 real"; else -> "$reais reais" }
        val c = when (centavos) { 0 -> null; 1 -> "1 centavo"; else -> "$centavos centavos" }
        return listOfNotNull(r, c).joinToString(" e ").ifEmpty { "0 reais" }
    }
}

/**
 * Monta a frase da meta com os números de hoje: lucro das corridas realizadas, menos a gasolina
 * do deslocamento se o check do dia estiver marcado, menos as deduções — igual à aba Corridas.
 */
class GoalVoiceUseCase(
    private val history: RideHistoryUseCase,
    private val goals: GoalSettingsRepository,
    private val dailyCommute: DailyCommuteUseCase,
    private val logger: AppLogger,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun announcement(today: LocalDate = LocalDate.now(zone)): String? = try {
        val page = history.page(HistoryPeriod.DAY, today)
        val ridesKm = page.rides.filter { it.done && Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() == today }
            .fold(BigDecimal.ZERO) { acc, r -> acc + r.totalKm }
        val commute = if (ridesKm.signum() > 0) {
            dailyCommute.forPeriod(mapOf(today to ridesKm)) { from, to -> history.doneKmBetween(from, to) }.included
        } else {
            null
        }
        val net = page.summary.netProfit - (commute?.fuelCost ?: BigDecimal.ZERO)
        GoalAnnouncement.text(DailyGoalCalculator.progress(net, goals.getProfitDeductions(), goals.getDailyGoal()))
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao montar a frase da meta", e)
        null
    }

    private companion object {
        const val TAG = "GoalVoice"
    }
}
