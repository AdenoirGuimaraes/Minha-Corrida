package br.com.minhacorrida.core.format

import br.com.minhacorrida.core.history.HistoryPeriod
import br.com.minhacorrida.core.history.RideHistoryPage
import br.com.minhacorrida.core.history.RideRecord
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Textos da aba Corridas (pt-BR). */
class RideHistoryFormatter(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale("pt", "BR"),
) {
    private val dayMonth = DateTimeFormatter.ofPattern("dd/MM", locale)
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)

    /** "Hoje · 05/11", "Ontem · 04/11", "Qui · 30/10"; "02/11 a 08/11"; "Novembro de 2026". */
    fun periodLabel(page: RideHistoryPage, today: LocalDate): String {
        val range = page.range
        return when (page.period) {
            HistoryPeriod.DAY -> {
                val day = range.start
                val prefix = when (day) {
                    today -> "Hoje"
                    today.minusDays(1) -> "Ontem"
                    else -> day.format(DateTimeFormatter.ofPattern("EEE", locale)).replaceFirstChar { it.titlecase(locale) }.removeSuffix(".")
                }
                "$prefix · ${day.format(dayMonth)}"
            }
            HistoryPeriod.WEEK -> "${range.start.format(dayMonth)} a ${range.endInclusive.format(dayMonth)}"
            HistoryPeriod.MONTH ->
                range.start.format(DateTimeFormatter.ofPattern("MMMM 'de' yyyy", locale)).replaceFirstChar { it.titlecase(locale) }
        }
    }

    /** "3 corridas realizadas · 18,4 km" */
    fun countAndKm(page: RideHistoryPage): String {
        val s = page.summary
        val rides = if (s.doneCount == 1) "1 corrida realizada" else "${s.doneCount} corridas realizadas"
        return "$rides · ${km(s.totalKm)} km"
    }

    fun profit(page: RideHistoryPage): String = "R$ ${money(page.summary.netProfit)}"

    fun cost(page: RideHistoryPage): String = "R$ ${money(page.summary.totalCost)}"

    /** "Combustível R$ 9,30 + manutenção R$ 4,60 · corridas R$ 48,00" (+ aviso de corridas sem custo). */
    fun costDetails(page: RideHistoryPage): String {
        val s = page.summary
        val base = "Combustível R$ ${money(s.fuelCost)} + manutenção R$ ${money(s.maintenanceCost)}" +
            " · recebido R$ ${money(s.totalFare)}"
        return if (s.ridesWithoutCost > 0) {
            "$base\n${s.ridesWithoutCost} corrida(s) sem gasto: os custos do carro não estavam preenchidos"
        } else {
            base
        }
    }

    /**
     * "🟢 18:24 · Uber · R$ 10,00 · 4,1 km · lucro R$ 6,26" (mês/semana mostram a data).
     * 🔴 = pedido do inDrive que estava abaixo do mínimo; "(era R$ 15,00)" = valor trocado.
     */
    fun row(record: RideRecord, period: HistoryPeriod): String {
        val at = Instant.ofEpochMilli(record.timestampMillis).atZone(zone)
        val whenText = if (period == HistoryPeriod.DAY) at.format(time) else "${at.format(dayMonth)} ${at.format(time)}"
        val profit = if (record.totalCost == null) "sem custos" else "lucro R$ ${money(record.netProfit)}"
        val color = if (record.good) "🟢" else "🔴"
        val original = record.originalFare?.let { " (era R$ ${money(it)})" }.orEmpty()
        return "$color $whenText · ${record.platform.displayName} · R$ ${money(record.fare)}$original" +
            " · ${km(record.totalKm)} km · $profit"
    }

    private fun money(value: BigDecimal) = DecimalFormat("#,##0.00", DecimalFormatSymbols(locale)).format(value)
    private fun km(value: BigDecimal) = DecimalFormat("#,##0.#", DecimalFormatSymbols(locale)).format(value)
}
