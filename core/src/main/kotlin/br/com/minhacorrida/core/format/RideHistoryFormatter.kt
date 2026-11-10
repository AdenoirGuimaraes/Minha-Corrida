package br.com.minhacorrida.core.format

import br.com.minhacorrida.core.goal.GoalProgress
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

    /**
     * Total a separar do recebido: combustível + manutenção + deduções (ex.: dízimo).
     * Ex. (10/11): 10,87 + 5,31 + 4,35 = "R$ 20,53".
     */
    fun setAside(page: RideHistoryPage, progress: GoalProgress): String =
        "R$ ${money(page.summary.totalCost + progress.totalDeductions)}"

    /**
     * O total de [setAside] item por item, cada um na sua linha, e o que fica com o motorista:
     * "⛽ Combustível R$ 10,87\n🔧 Manutenção R$ 5,31\n📌 Dízimo (10%) R$ 4,35\n
     * Recebido R$ 59,70 · fica com você R$ 39,17" (+ aviso de corridas sem custo).
     */
    fun setAsideDetails(page: RideHistoryPage, progress: GoalProgress): String {
        val s = page.summary
        val lines = mutableListOf(
            "⛽ Combustível R$ ${money(s.fuelCost)}",
            "🔧 Manutenção R$ ${money(s.maintenanceCost)}",
        )
        progress.deductions.forEach { d ->
            lines += "📌 ${d.deduction.label} (${percent(d.deduction.percent)}%) R$ ${money(d.amount)}"
        }
        lines += if (progress.deductions.isEmpty()) {
            "Recebido R$ ${money(s.totalFare)}"
        } else {
            "Recebido R$ ${money(s.totalFare)} · fica com você R$ ${money(progress.kept)}"
        }
        if (s.ridesWithoutCost > 0) {
            lines += "${s.ridesWithoutCost} corrida(s) sem gasto: os custos do carro não estavam preenchidos"
        }
        return lines.joinToString("\n")
    }

    /**
     * "🟢 18:24 · Uber · R$ 10,00 · 4,1 km · lucro R$ 6,26" (mês/semana mostram a data).
     * 🔴 = pedido do inDrive que estava abaixo do mínimo; "(era R$ 15,00)" = valor trocado.
     */
    fun row(record: RideRecord, period: HistoryPeriod): String {
        val at = Instant.ofEpochMilli(record.timestampMillis).atZone(zone)
        val whenText = if (period == HistoryPeriod.DAY) at.format(time) else "${at.format(dayMonth)} ${at.format(time)}"
        val profit = if (record.totalCost == null) "sem custos" else "lucro R$ ${money(record.netProfit)}"
        val color = when {
            !record.good -> "🔴"
            record.lowProfit -> "🟡"
            else -> "🟢"
        }
        val original = record.originalFare?.let { " (era R$ ${money(it)})" }.orEmpty()
        return "$color $whenText · ${record.platform.displayName} · R$ ${money(record.fare)}$original" +
            " · ${km(record.totalKm)} km · $profit"
    }

    /**
     * Card da corrida guardada, igual ao card da oferta (faixa do app + 3 quadrados), com os
     * valores e gastos GUARDADOS da corrida. O 3º quadrado ("Mín. verde") usa as regras de agora.
     * Ex. (inDrive R$ 13 era R$ 10,50, 4,5 km, gasto R$ 3,83):
     * faixa "inDrive — R$ 13,00 (era 10,50)" · "15:19"; R$/km "2,60" / "4,5 km\n(1,2 + 3,3)";
     * Lucro "7,87" / "Líquido R$ 11,70\nGasto R$ 3,83"; Mín. verde "…" / "R$ 2,00/km\nLucro R$ 7,00".
     *
     * @param minValuePerKm mínimo R$/km de agora; [minNetProfit] lucro mínimo de agora (ou null).
     */
    fun card(
        record: RideRecord,
        period: HistoryPeriod,
        minValuePerKm: BigDecimal,
        minNetProfit: BigDecimal?,
    ): RideCardText {
        val at = Instant.ofEpochMilli(record.timestampMillis).atZone(zone)
        val whenText = if (period == HistoryPeriod.DAY) at.format(time) else "${at.format(dayMonth)} ${at.format(time)}"
        val pickup = record.pickupKm?.takeIf { it.signum() > 0 }
        val kmDetails = if (pickup != null) {
            "${km(record.totalKm)} km\n(${km(pickup)} + ${km(record.tripKm)})"
        } else {
            "${km(record.totalKm)} km\n(só trajeto)"
        }
        val fareLine = if (record.feePercent.signum() > 0) {
            "Líquido R$ ${money(record.netFare)}"
        } else {
            "Corrida R$ ${money(record.fare)}"
        }
        val cost = record.totalCost
        val effectiveMinProfit = minNetProfit?.takeIf { cost != null }
        val green = br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase.greenFare(
            record.totalKm, record.feePercent, minValuePerKm, cost, effectiveMinProfit,
        )
        val original = record.originalFare?.let { " (era ${money(it)})" }.orEmpty()
        return RideCardText(
            platform = "${record.platform.displayName} — R$ ${money(record.fare)}$original",
            rating = whenText,
            valuePerKm = money(record.valuePerKm),
            kmDetails = kmDetails,
            profitLabel = if (cost != null && record.netProfit.signum() < 0) "Prejuízo" else "Lucro",
            profitValue = if (cost != null) money(record.netProfit.abs()) else "—",
            profitDetails = if (cost != null) "$fareLine\nGasto R$ ${money(cost)}" else "$fareLine\nSem custos",
            minimumValue = money(green),
            minimumLabel = "R$ ${money(minValuePerKm)}/km" +
                effectiveMinProfit?.let { "\nLucro R$ ${money(it)}" }.orEmpty(),
        )
    }

    private fun money(value: BigDecimal) = DecimalFormat("#,##0.00", DecimalFormatSymbols(locale)).format(value)
    private fun km(value: BigDecimal) = DecimalFormat("#,##0.#", DecimalFormatSymbols(locale)).format(value)
    private fun percent(value: BigDecimal) = DecimalFormat("#,##0.##", DecimalFormatSymbols(locale)).format(value)
}
