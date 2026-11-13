package br.com.minhacorrida.core.insights

import br.com.minhacorrida.core.history.RideRecord
import br.com.minhacorrida.core.model.Platform
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Um grupo de corridas realizadas (faixa de horário, dia da semana ou app) e suas médias.
 *
 * @property perKm R$/km médio ponderado: total líquido ÷ km total (não a média das médias).
 * @property avgProfit lucro médio por corrida (só corridas com custos informados).
 * @property profitPerHour lucro por hora EM corrida (só corridas com minutos); null sem minutos.
 * @property feePaid taxa paga à plataforma (valor − líquido), para comparar Uber × inDrive.
 */
data class InsightRow(
    val label: String,
    val rides: Int,
    val km: BigDecimal,
    val net: BigDecimal,
    val perKm: BigDecimal,
    val avgProfit: BigDecimal?,
    val profitPerHour: BigDecimal?,
    val feePaid: BigDecimal,
)

/**
 * Análises do histórico (pedidos de 13/11, itens 6 e 8 do backlog):
 * melhores horários, melhores dias da semana e Uber × inDrive. Só corridas REALIZADAS.
 */
object RideInsights {
    /** Grupos com menos corridas que isso não concorrem a "melhor" (pouca amostra engana). */
    const val MIN_SAMPLE = 3

    /** Faixas de horário de 3 h (00–06 junto: pouco movimento). Início inclusive. */
    private val BANDS = listOf(0 to 6, 6 to 9, 9 to 12, 12 to 15, 15 to 18, 18 to 21, 21 to 24)

    private val DAY_NAMES = mapOf(
        DayOfWeek.MONDAY to "Segunda", DayOfWeek.TUESDAY to "Terça", DayOfWeek.WEDNESDAY to "Quarta",
        DayOfWeek.THURSDAY to "Quinta", DayOfWeek.FRIDAY to "Sexta", DayOfWeek.SATURDAY to "Sábado",
        DayOfWeek.SUNDAY to "Domingo",
    )

    fun byTimeBand(rides: List<RideRecord>, zone: ZoneId): List<InsightRow> {
        val done = rides.filter { it.done }
        return BANDS.mapNotNull { (from, to) ->
            val list = done.filter { hourOf(it, zone) in from until to }
            row("%02dh–%02dh".format(from, to), list)
        }
    }

    fun byWeekday(rides: List<RideRecord>, zone: ZoneId): List<InsightRow> {
        val done = rides.filter { it.done }
        return DayOfWeek.entries.mapNotNull { day ->
            row(DAY_NAMES.getValue(day), done.filter { dayOf(it, zone) == day })
        }
    }

    fun byPlatform(rides: List<RideRecord>): List<InsightRow> {
        val done = rides.filter { it.done }
        return Platform.entries.mapNotNull { p -> row(p.displayName, done.filter { it.platform == p }) }
    }

    /** O grupo de maior R$/km entre os que têm ao menos [MIN_SAMPLE] corridas. */
    fun best(rows: List<InsightRow>): InsightRow? = rows.filter { it.rides >= MIN_SAMPLE }.maxByOrNull { it.perKm }

    /**
     * Texto da janela "Análises" da aba Corridas.
     * @param periodLabel ex.: "Novembro/2026".
     */
    fun text(rides: List<RideRecord>, zone: ZoneId, periodLabel: String): String {
        val done = rides.filter { it.done }
        if (done.isEmpty()) return "Nenhuma corrida realizada em $periodLabel."
        return buildString {
            appendLine("$periodLabel · ${done.size} corrida(s) realizada(s)")
            appendLine("⭐ = melhor R$/km (com pelo menos $MIN_SAMPLE corridas)")
            section("🕘 Por horário", byTimeBand(done, zone))
            section("📅 Por dia da semana", byWeekday(done, zone))
            section("📱 Uber × inDrive", byPlatform(done), withFee = true)
        }.trimEnd()
    }

    private fun StringBuilder.section(title: String, rows: List<InsightRow>, withFee: Boolean = false) {
        appendLine()
        appendLine(title)
        val best = best(rows)
        rows.forEach { r ->
            val star = if (r == best) " ⭐" else ""
            val parts = mutableListOf("${r.rides} corr.", "R$ ${money(r.perKm)}/km")
            r.avgProfit?.let { parts += "lucro méd. R$ ${money(it)}" }
            r.profitPerHour?.let { parts += "R$ ${money(it)}/h" }
            if (withFee && r.feePaid.signum() > 0) parts += "taxa R$ ${money(r.feePaid)}"
            appendLine("${r.label}$star: ${parts.joinToString(" · ")}")
        }
    }

    private fun row(label: String, list: List<RideRecord>): InsightRow? {
        if (list.isEmpty()) return null
        val km = sum(list.map { it.totalKm })
        val net = sum(list.map { it.netFare })
        val withCost = list.filter { it.totalCost != null }
        val avgProfit = withCost.takeIf { it.isNotEmpty() }
            ?.let { sum(it.map { r -> r.netProfit }).divide(BigDecimal(it.size), 2, RoundingMode.HALF_UP) }
        val timed = withCost.filter { (it.minutes ?: 0) > 0 }
        val minutes = timed.sumOf { it.minutes ?: 0 }
        val perHour = if (minutes > 0) {
            sum(timed.map { it.netProfit }).multiply(BigDecimal(60)).divide(BigDecimal(minutes), 2, RoundingMode.HALF_UP)
        } else {
            null
        }
        return InsightRow(
            label = label,
            rides = list.size,
            km = km.setScale(1, RoundingMode.HALF_UP),
            net = net.setScale(2, RoundingMode.HALF_UP),
            perKm = if (km.signum() > 0) net.divide(km, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2),
            avgProfit = avgProfit,
            profitPerHour = perHour,
            feePaid = sum(list.map { it.fare - it.netFare }).setScale(2, RoundingMode.HALF_UP),
        )
    }

    private fun hourOf(r: RideRecord, zone: ZoneId) = Instant.ofEpochMilli(r.timestampMillis).atZone(zone).hour
    private fun dayOf(r: RideRecord, zone: ZoneId) = Instant.ofEpochMilli(r.timestampMillis).atZone(zone).dayOfWeek
    private fun sum(values: List<BigDecimal>) = values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    private fun money(v: BigDecimal) = v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
