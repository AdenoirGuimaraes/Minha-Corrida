package br.com.minhacorrida.core.report

import br.com.minhacorrida.core.goal.DailyGoalCalculator
import br.com.minhacorrida.core.goal.GoalProgress
import br.com.minhacorrida.core.goal.GoalSettingsRepository
import br.com.minhacorrida.core.history.DateRange
import br.com.minhacorrida.core.history.RideHistoryRepository
import br.com.minhacorrida.core.history.RideHistoryUseCase
import br.com.minhacorrida.core.history.RideRecord
import br.com.minhacorrida.core.km.CommuteCost
import br.com.minhacorrida.core.km.FuelLogRepository
import br.com.minhacorrida.core.km.FuelStatsCalculator
import br.com.minhacorrida.core.km.KmCorrection
import br.com.minhacorrida.core.km.KmLogRepository
import br.com.minhacorrida.core.km.KmSettingsRepository
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Resultado de um dia no relatório (mesmas contas da aba Corridas, filtro Dia). */
private data class DayResult(
    val date: LocalDate,
    val rides: List<RideRecord>,
    val gpsKm: BigDecimal,
    val commute: CommuteCost?,
    val progress: GoalProgress,
) {
    val done = rides.filter { it.done }
    val summary = RideHistoryUseCase.summarize(rides)
}

/**
 * Relatório de corridas em Markdown (semana, mês ou período escolhido) para o motorista
 * enviar a uma IA analisar: configurações usadas, resumo, por app, por dia, cada corrida com
 * todos os números do card, corridas não realizadas, abastecimentos e como as contas são feitas.
 *
 * As contas são as mesmas da aba Corridas: lucro = líquido − gasolina − manutenção da corrida;
 * deslocamento (controle de km) e deduções por dia, sobre o lucro positivo do dia.
 */
class RideReportUseCase(
    private val rides: RideHistoryRepository,
    private val settings: SettingsRepository,
    private val goals: GoalSettingsRepository,
    private val kmSettings: KmSettingsRepository,
    private val kmLog: KmLogRepository,
    private val fuelLog: FuelLogRepository,
    private val dailyCommute: br.com.minhacorrida.core.km.DailyCommuteUseCase,
    private val logger: AppLogger,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale("pt", "BR"),
    /** Manutenções anotadas (com comprovante); null = não mostra a seção. */
    private val maintenanceLog: br.com.minhacorrida.core.receipt.MaintenanceLogRepository? = null,
    /** Quais registros têm foto do comprovante. */
    private val receipts: br.com.minhacorrida.core.receipt.ReceiptStore = br.com.minhacorrida.core.receipt.ReceiptStore.NONE,
) {
    /**
     * @param range dias do relatório [start, endExclusive).
     * @param appVersion ex. "0.2.43 (43)"; [generatedAtMillis] momento da geração.
     * @return o relatório em Markdown (nunca lança: em erro, devolve o relatório com o aviso).
     */
    fun markdown(range: DateRange, appVersion: String, generatedAtMillis: Long): String = try {
        build(range, appVersion, generatedAtMillis)
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao gerar relatório", e)
        "# Minha Corrida — relatório\n\nNão foi possível gerar o relatório: ${e.message}\n"
    }

    private fun build(range: DateRange, appVersion: String, generatedAtMillis: Long): String {
        val all = rides.between(range.startMillis(zone), range.endMillis(zone)).sortedBy { it.timestampMillis }
        val cost = settings.getOperatingCost()
        val deductions = goals.getProfitDeductions()
        val dailyGoal = goals.getDailyGoal()
        val tracking = kmSettings.isKmTrackingEnabled()
        val fuelEntries = fuelLog.all()
        val stats = FuelStatsCalculator.of(fuelEntries)
        val correction = if (stats.from != null && stats.to != null) {
            KmCorrection.factor(stats, kmLog.kmBetween(stats.from, stats.to))
        } else {
            null
        }

        // Deslocamento por dia, igual à aba Corridas: só entra nas contas nos dias com o check marcado.
        val ridesKmByDay = all.filter { it.done }.groupBy(::dateOf)
            .mapValues { (_, list) -> sum(list.map { it.totalKm }) }
        val commutes = dailyCommute.forPeriod(ridesKmByDay) { from, to ->
            sum(rides.between(DateRange(from, to).startMillis(zone), DateRange(from, to).endMillis(zone)).filter { it.done }.map { it.totalKm })
        }.days

        // Um resultado por dia do período (as deduções e a meta são diárias, como na aba Corridas).
        val days = generateSequence(range.start) { it.plusDays(1) }.takeWhile { it < range.endExclusive }.map { day ->
            val dayRides = all.filter { dateOf(it) == day }
            val summary = RideHistoryUseCase.summarize(dayRides)
            val gps = if (tracking) kmLog.kmOn(day) else BigDecimal.ZERO
            val commute = commutes[day]?.takeIf { it.second }?.first
            val progress = DailyGoalCalculator.progress(
                summary.netProfit - (commute?.fuelCost ?: BigDecimal.ZERO), deductions, dailyGoal,
            )
            DayResult(day, dayRides, gps, commute, progress)
        }.toList()

        return buildString {
            header(range, appVersion, generatedAtMillis)
            configuration(cost, deductions, dailyGoal, tracking, correction)
            totals(days, dailyGoal, deductions.isNotEmpty())
            byPlatform(all.filter { it.done })
            insights("Por horário", br.com.minhacorrida.core.insights.RideInsights.byTimeBand(all, zone))
            insights("Por dia da semana", br.com.minhacorrida.core.insights.RideInsights.byWeekday(all, zone))
            byDay(days, dailyGoal, tracking)
            rideTable("Corridas realizadas", all.filter { it.done })
            val notDone = all.filterNot { it.done }
            if (notDone.isNotEmpty()) rideTable("Corridas NÃO marcadas como realizadas (fora das contas)", notDone)
            fuel(range, fuelEntries, stats)
            maintenance(range)
            notes()
        }
    }

    // ---- Seções ----

    private fun StringBuilder.header(range: DateRange, appVersion: String, generatedAtMillis: Long) {
        val daysCount = ChronoUnit.DAYS.between(range.start, range.endExclusive)
        appendLine("# Minha Corrida — relatório de corridas")
        appendLine()
        appendLine("- Período: ${range.start.format(DATE)} a ${range.endInclusive.format(DATE)} ($daysCount dias)")
        appendLine("- Gerado em: ${Instant.ofEpochMilli(generatedAtMillis).atZone(zone).format(DATE_TIME)}")
        appendLine("- App: Minha Corrida $appVersion (Android, motorista de Uber e inDrive em Lages/SC)")
        appendLine("- Valores em R$; distâncias em km. O histórico guarda 60 dias.")
        appendLine()
    }

    private fun StringBuilder.configuration(
        cost: br.com.minhacorrida.core.model.OperatingCost?,
        deductions: List<br.com.minhacorrida.core.goal.ProfitDeduction>,
        dailyGoal: BigDecimal?,
        tracking: Boolean,
        correction: BigDecimal?,
    ) {
        appendLine("## Configurações usadas (atuais)")
        appendLine()
        appendLine("| Regra | Valor |")
        appendLine("|---|---|")
        appendLine("| Mínimo R$/km (vermelho abaixo) | ${money(settings.getMinValuePerKm())} |")
        appendLine("| Lucro líquido mínimo por corrida (amarelo abaixo) | ${settings.getMinNetProfit()?.let(::money) ?: "não usado"} |")
        appendLine("| Lucro mínimo por minuto (amarelo abaixo) | ${settings.getMinProfitPerMinute()?.let { money(it) + "/min" } ?: "não usado"} |")
        appendLine("| Nota mínima do passageiro | ${settings.getMinPassengerRating()?.let(::decimal) ?: "não usada"} |")
        appendLine("| Taxa do inDrive | ${decimal(settings.getInDriveFeePercent())}% (Uber: valor do card já é o ganho) |")
        if (cost != null) {
            appendLine("| Gasolina | R$ ${money(cost.fuelPricePerLiter)}/L, ${decimal(cost.kmPerLiter)} km/L |")
            appendLine("| Manutenção e outros | R$ ${money(cost.otherCostPerKm)}/km |")
            appendLine("| Custo total do carro | R$ ${money(cost.costPerKm)}/km |")
        } else {
            appendLine("| Custos do carro | não informados (sem lucro calculado) |")
        }
        appendLine("| Meta diária (fica com você) | ${dailyGoal?.let(::money) ?: "não usada"} |")
        appendLine(
            "| Deduções do lucro | ${
                if (deductions.isEmpty()) "nenhuma" else deductions.joinToString { "${it.label} ${decimal(it.percent)}%" }
            } |",
        )
        appendLine(
            "| Controle de km (GPS por trajeto) | ${if (tracking) "ligado" else "desligado"}${
                correction?.let { ", ajuste do painel ${KmCorrection.percentLabel(it)}" }.orEmpty()
            } |",
        )
        appendLine()
    }

    private fun StringBuilder.totals(days: List<DayResult>, dailyGoal: BigDecimal?, hasDeductions: Boolean) {
        val done = days.flatMap { it.done }
        val worked = days.filter { it.done.isNotEmpty() }
        val fare = sum(days.map { it.summary.totalFare })
        val fuel = sum(days.map { it.summary.fuelCost })
        val maintenance = sum(days.map { it.summary.maintenanceCost })
        val profit = sum(days.map { it.summary.netProfit })
        val commuteKm = sum(days.mapNotNull { it.commute?.commuteKm })
        val commuteFuel = sum(days.mapNotNull { it.commute?.fuelCost })
        val deductionTotal = sum(days.map { it.progress.totalDeductions })
        val kept = sum(days.map { it.progress.kept })
        val ridesKm = sum(days.map { it.summary.totalKm })
        val gpsKm = sum(days.map { it.gpsKm })
        val fees = sum(done.map { it.fare - it.netFare })

        appendLine("## Resumo do período")
        appendLine()
        appendLine("| Item | Valor |")
        appendLine("|---|---|")
        appendLine("| Corridas realizadas | ${done.size} em ${worked.size} dia(s) |")
        appendLine("| Km das corridas (busca + viagem) | ${km(ridesKm)} |")
        if (gpsKm.signum() > 0) appendLine("| Km rodados no total (GPS, trajetos registrados) | ${km(gpsKm)} |")
        if (commuteKm.signum() > 0) appendLine("| Km de deslocamento sem passageiro | ${km(commuteKm)} |")
        appendLine("| Valor das corridas (app) | ${money(sum(done.map { it.fare }))} |")
        if (fees.signum() > 0) appendLine("| Taxas do inDrive | ${money(fees)} |")
        appendLine("| **Recebido (líquido)** | **${money(fare)}** |")
        appendLine("| Gasolina das corridas | ${money(fuel)} |")
        appendLine("| Manutenção das corridas | ${money(maintenance)} |")
        appendLine("| Lucro das corridas (recebido − gasolina − manutenção) | ${money(profit)} |")
        if (commuteFuel.signum() > 0) appendLine("| Gasolina do deslocamento | ${money(commuteFuel)} |")
        if (hasDeductions) {
            // Soma por dedução (ex.: Dízimo) ao longo dos dias.
            days.flatMap { it.progress.deductions }.groupBy { it.deduction.label to it.deduction.percent }
                .forEach { (key, items) ->
                    appendLine("| ${key.first} (${decimal(key.second)}%) | ${money(sum(items.map { it.amount }))} |")
                }
        }
        appendLine("| **Separar** (gasolina + manutenção + deslocamento + deduções) | **${money(fuel + maintenance + commuteFuel + deductionTotal)}** |")
        appendLine("| **Fica com você** | **${money(kept)}** |")
        if (done.isNotEmpty()) {
            val count = BigDecimal(done.size)
            appendLine("| Média por corrida: recebido / lucro / km | ${money(fare.divide(count, 2, RoundingMode.HALF_UP))} / ${money(profit.divide(count, 2, RoundingMode.HALF_UP))} / ${km(ridesKm.divide(count, 1, RoundingMode.HALF_UP))} |")
            if (ridesKm.signum() > 0) appendLine("| R$/km médio (recebido ÷ km das corridas) | ${money(fare.divide(ridesKm, 2, RoundingMode.HALF_UP))} |")
        }
        // Minutos (corridas gravadas a partir da 0.2.45; as manuais e antigas não têm).
        val timed = done.filter { it.minutes != null && it.totalCost != null }
        if (timed.isNotEmpty()) {
            val minutes = timed.sumOf { it.minutes!! }
            val timedProfit = sum(timed.map { it.netProfit })
            appendLine(
                "| Minutos de corrida (busca + viagem) / lucro por minuto | $minutes min em ${timed.size} corrida(s) / " +
                    "${money(timedProfit.divide(BigDecimal(minutes.coerceAtLeast(1)), 2, RoundingMode.HALF_UP))}/min |",
            )
        }
        if (worked.isNotEmpty()) {
            val workedCount = BigDecimal(worked.size)
            appendLine("| Média por dia trabalhado: recebido / fica com você | ${money(fare.divide(workedCount, 2, RoundingMode.HALF_UP))} / ${money(sum(worked.map { it.progress.kept }).divide(workedCount, 2, RoundingMode.HALF_UP))} |")
        }
        if (dailyGoal != null) {
            appendLine("| Meta diária batida | ${worked.count { it.progress.reached }} de ${worked.size} dia(s) trabalhado(s) |")
        }
        val colors = done.groupingBy { it.rating }.eachCount()
        appendLine("| Cores das realizadas | 🟢 ${colors[RideRating.GOOD] ?: 0} · 🟡 ${colors[RideRating.LOW_PROFIT] ?: 0} · 🔴 ${colors[RideRating.BELOW_MINIMUM] ?: 0} |")
        appendLine()
    }

    private fun StringBuilder.byPlatform(done: List<RideRecord>) {
        if (done.isEmpty()) return
        appendLine("## Por app")
        appendLine()
        appendLine("| App | Corridas | Km | Recebido | Lucro | R$/km | Lucro médio |")
        appendLine("|---|---|---|---|---|---|---|")
        Platform.entries.forEach { platform ->
            val list = done.filter { it.platform == platform }
            if (list.isEmpty()) return@forEach
            val s = RideHistoryUseCase.summarize(list)
            val perKm = if (s.totalKm.signum() > 0) money(s.totalFare.divide(s.totalKm, 2, RoundingMode.HALF_UP)) else "—"
            appendLine(
                "| ${platform.displayName} | ${list.size} | ${km(s.totalKm)} | ${money(s.totalFare)} | ${money(s.netProfit)} | $perKm | " +
                    "${money(s.netProfit.divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP))} |",
            )
        }
        appendLine()
    }

    /** Tabela de uma análise (horário ou dia da semana); ⭐ = melhor R$/km com amostra mínima. */
    private fun StringBuilder.insights(title: String, rows: List<br.com.minhacorrida.core.insights.InsightRow>) {
        if (rows.isEmpty()) return
        val best = br.com.minhacorrida.core.insights.RideInsights.best(rows)
        appendLine("## $title")
        appendLine()
        appendLine("| ${title.removePrefix("Por ").replaceFirstChar { it.uppercase() }} | Corridas | Km | R$/km | Lucro médio | Lucro/hora em corrida |")
        appendLine("|---|---|---|---|---|---|")
        rows.forEach { r ->
            appendLine(
                "| ${r.label}${if (r == best) " ⭐" else ""} | ${r.rides} | ${km(r.km)} | ${money(r.perKm)} | " +
                    "${r.avgProfit?.let(::money) ?: "—"} | ${r.profitPerHour?.let(::money) ?: "—"} |",
            )
        }
        appendLine()
    }

    private fun StringBuilder.byDay(days: List<DayResult>, dailyGoal: BigDecimal?, tracking: Boolean) {
        val active = days.filter { it.done.isNotEmpty() || it.gpsKm.signum() > 0 }
        if (active.isEmpty()) return
        appendLine("## Por dia")
        appendLine()
        appendLine("| Data | Corridas | Km corridas | ${if (tracking) "Km GPS | Deslocamento R$ | " else ""}Recebido | Gasolina + manut. | Lucro | Deduções | Fica com você |${if (dailyGoal != null) " Meta |" else ""}")
        appendLine("|---|---|---|${if (tracking) "---|---|" else ""}---|---|---|---|---|${if (dailyGoal != null) "---|" else ""}")
        active.forEach { d ->
            val s = d.summary
            val trackingCols = if (tracking) "${km(d.gpsKm)} | ${d.commute?.let { money(it.fuelCost) } ?: "não descontado"} | " else ""
            val goalCol = if (dailyGoal != null) " ${if (d.progress.reached) "✅" else "${d.progress.progressPercent}%"} |" else ""
            appendLine(
                "| ${d.date.format(DAY_NAME)} | ${d.done.size} | ${km(s.totalKm)} | $trackingCols${money(s.totalFare)} | " +
                    "${money(s.fuelCost + s.maintenanceCost)} | ${money(s.netProfit)} | ${money(d.progress.totalDeductions)} | " +
                    "${money(d.progress.kept)} |$goalCol",
            )
        }
        appendLine()
    }

    private fun StringBuilder.rideTable(title: String, list: List<RideRecord>) {
        appendLine("## $title (${list.size})")
        appendLine()
        if (list.isEmpty()) {
            appendLine("Nenhuma.")
            appendLine()
            return
        }
        appendLine("| Data | Hora | App | Valor | Era (contraproposta) | Taxa | Líquido | Busca km | Viagem km | Total km | Min | R$/km | Gasolina | Manut. | Lucro | Lucro/min | Cor |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        list.forEach { r ->
            val at = Instant.ofEpochMilli(r.timestampMillis).atZone(zone)
            val color = when (r.rating) {
                RideRating.GOOD -> "🟢"
                RideRating.LOW_PROFIT -> "🟡"
                RideRating.BELOW_MINIMUM -> "🔴"
            }
            appendLine(
                "| ${at.format(DATE_SHORT)} | ${at.format(TIME)} | ${r.platform.displayName} | ${money(r.fare)} | " +
                    "${r.originalFare?.let(::money) ?: "—"} | ${if (r.feePercent.signum() > 0) decimal(r.feePercent) + "%" else "—"} | " +
                    "${money(r.netFare)} | ${r.pickupKm?.let(::km) ?: "—"} | ${km(r.tripKm)} | ${km(r.totalKm)} | " +
                    "${r.minutes?.let { (if (r.minutesEstimated) "≈" else "") + it } ?: "—"} | " +
                    "${money(r.valuePerKm)} | ${r.fuelCost?.let(::money) ?: "—"} | ${r.maintenanceCost?.let(::money) ?: "—"} | " +
                    "${if (r.totalCost != null) money(r.netProfit) else "—"} | ${r.profitPerMinute?.let(::money) ?: "—"} | $color |",
            )
        }
        appendLine()
    }

    private fun StringBuilder.fuel(range: DateRange, entries: List<br.com.minhacorrida.core.km.FuelEntry>, stats: br.com.minhacorrida.core.km.FuelStats) {
        val inRange = entries.filter { it.date >= range.start && it.date < range.endExclusive }.sortedBy { it.date }
        appendLine("## Abastecimentos no período (${inRange.size})")
        appendLine()
        if (inRange.isNotEmpty()) {
            appendLine("| Data | Km do painel | Litros | Valor | R$/L | Tanque cheio | Comprovante |")
            appendLine("|---|---|---|---|---|---|---|")
            inRange.forEach { e ->
                appendLine("| ${e.date.format(DATE)} | ${km(e.odometerKm)} | ${decimal(e.liters)} | ${money(e.totalPrice)} | ${money(e.pricePerLiter)} | ${if (e.fullTank) "sim" else "não"} | ${receipt(e.id)} |")
            }
            appendLine("| **Total** | | ${decimal(sum(inRange.map { it.liters }))} | ${money(sum(inRange.map { it.totalPrice }))} | | | |")
            appendLine()
        }
        if (stats.kmPerLiter != null) {
            appendLine("- Consumo real (tanque cheio, todo o histórico): ${decimal(stats.kmPerLiter)} km/L; preço médio R$ ${stats.pricePerLiter?.let(::money)}/L; painel ${stats.odometerKm?.let(::km)} km de ${stats.from?.format(DATE)} a ${stats.to?.format(DATE)}.")
            appendLine()
        }
    }

    /** Manutenções do período com valor e se há foto do comprovante. */
    private fun StringBuilder.maintenance(range: DateRange) {
        val log = maintenanceLog ?: return
        val inRange = try {
            log.all().filter { it.date >= range.start && it.date < range.endExclusive }.sortedBy { it.date }
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao ler manutenções", e)
            emptyList()
        }
        if (inRange.isEmpty()) return
        appendLine("## Manutenções no período (${inRange.size})")
        appendLine()
        appendLine("| Data | Descrição | Valor | Comprovante |")
        appendLine("|---|---|---|---|")
        inRange.forEach { m -> appendLine("| ${m.date.format(DATE)} | ${m.description.replace("|", "/")} | ${money(m.value)} | ${receipt(m.id)} |") }
        appendLine("| **Total** | | ${money(sum(inRange.map { it.value }))} | |")
        appendLine()
    }

    private fun receipt(id: String) = if (runCatching { receipts.has(id) }.getOrDefault(false)) "📷 sim" else "não"

    private fun StringBuilder.notes() {
        appendLine("## Como as contas são feitas")
        appendLine()
        appendLine("- **Recebido (líquido)**: Uber = valor do card (já é o ganho do motorista); inDrive = valor − taxa configurada.")
        appendLine("- **Gasolina/manutenção da corrida**: km total (busca até o passageiro + viagem) × custo por km do carro no momento da corrida.")
        appendLine("- **Lucro da corrida** = líquido − gasolina − manutenção. Não desconta deduções nem deslocamento.")
        appendLine("- **Deslocamento**: com o controle de km ligado, (km do GPS dos trajetos registrados × ajuste do painel − km das corridas) × preço do litro ÷ km/L; sem GPS no dia, média do painel. Só entra nas contas nos dias em que o motorista marcou \"Descontar a gasolina do deslocamento\".")
        appendLine("- **Deduções** (ex.: dízimo): % sobre o lucro POSITIVO de cada dia, depois do deslocamento; somadas no período.")
        appendLine("- **Fica com você** = lucro − deslocamento − deduções. **Separar** = gasolina + manutenção + deslocamento + deduções.")
        appendLine("- **Cores**: 🔴 R$/km abaixo do mínimo ou nota baixa; 🟡 R$/km ok mas lucro ou lucro/minuto abaixo do mínimo; 🟢 passa em tudo. Cores e valores guardados no momento da corrida.")
        appendLine("- Corridas entram sozinhas quando confirmadas na tela do app (Uber: \"Encontro com…\"; inDrive: passageiro aceitou) e são marcadas como realizadas na avaliação do passageiro; as manuais entram pelo botão Registrar Corrida.")
        appendLine("- **Minutos** (busca + viagem) vêm do card da oferta; \"≈\" = estimados a 25 km/h quando a tela não mostrou. Corridas registradas à mão e as gravadas antes da versão 0.2.45 não têm minutos.")
        appendLine()
    }

    // ---- Formatação ----

    private fun dateOf(r: RideRecord): LocalDate = Instant.ofEpochMilli(r.timestampMillis).atZone(zone).toLocalDate()
    private fun sum(values: List<BigDecimal>) = values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    private fun money(value: BigDecimal) = DecimalFormat("#,##0.00", DecimalFormatSymbols(locale)).format(value)
    private fun km(value: BigDecimal) = DecimalFormat("#,##0.#", DecimalFormatSymbols(locale)).format(value)
    private fun decimal(value: BigDecimal) = DecimalFormat("#,##0.##", DecimalFormatSymbols(locale)).format(value)

    private companion object {
        const val TAG = "RideReport"
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val DATE_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DAY_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd/MM", Locale("pt", "BR"))
    }
}
