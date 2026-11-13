package br.com.minhacorrida.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.port.SettingsRepository
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import br.com.minhacorrida.R
import br.com.minhacorrida.core.format.RideHistoryFormatter
import br.com.minhacorrida.core.history.DateRange
import br.com.minhacorrida.core.history.HistoryPeriod
import br.com.minhacorrida.core.history.rangeOf
import br.com.minhacorrida.core.history.RideHistoryPage
import br.com.minhacorrida.core.history.RideHistoryUseCase
import br.com.minhacorrida.core.history.RideRecord
import br.com.minhacorrida.core.history.shift
import java.time.LocalDate
import java.util.concurrent.ExecutorService

/**
 * Aba Corridas: corridas verdes guardadas, filtro Dia/Semana/Mês com ◀ ▶, check de
 * "realizei" em cada uma e o resumo das realizadas (km, lucro e quanto separar para
 * abastecer). Leitura e gravação do arquivo ficam fora da thread principal.
 */
class RideHistoryScreen(
    private val activity: Activity,
    private val history: RideHistoryUseCase,
    private val formatter: RideHistoryFormatter,
    private val io: ExecutorService,
    private val evaluate: EvaluateRideUseCase,
    private val settings: SettingsRepository,
    private val cardFormatter: RideAnalysisFormatter,
    private val goals: br.com.minhacorrida.core.goal.GoalSettingsRepository,
    private val goalFormatter: br.com.minhacorrida.core.format.GoalFormatter,
    private val kmSettings: br.com.minhacorrida.core.km.KmSettingsRepository,
    private val kmLog: br.com.minhacorrida.core.km.KmLogRepository,
    private val fuelLog: br.com.minhacorrida.core.km.FuelLogRepository,
    private val trips: br.com.minhacorrida.core.km.TripUseCase,
    private val parking: br.com.minhacorrida.infra.ParkingStore,
    private val report: br.com.minhacorrida.core.report.RideReportUseCase,
    private val appVersion: String,
    private val dailyCommute: br.com.minhacorrida.core.km.DailyCommuteUseCase,
    private val commuteChoices: br.com.minhacorrida.core.km.CommuteChoiceRepository,
) {
    private val main = Handler(Looper.getMainLooper())
    private val cardBinder = RideCardBinder(cardFormatter)
    private val periodTabs: Map<HistoryPeriod, TextView> = mapOf(
        HistoryPeriod.DAY to activity.findViewById(R.id.periodDay),
        HistoryPeriod.WEEK to activity.findViewById(R.id.periodWeek),
        HistoryPeriod.MONTH to activity.findViewById(R.id.periodMonth),
    )
    private val tvPeriodLabel: TextView = activity.findViewById(R.id.tvPeriodLabel)
    private val tvCountKm: TextView = activity.findViewById(R.id.tvHistCountKm)
    private val tvProfit: TextView = activity.findViewById(R.id.tvHistProfit)
    private val tvCost: TextView = activity.findViewById(R.id.tvHistCost)
    private val tvCostDetail: TextView = activity.findViewById(R.id.tvHistCostDetail)
    private val list: LinearLayout = activity.findViewById(R.id.historyList)
    private val tvEmpty: TextView = activity.findViewById(R.id.tvHistEmpty)
    private val goalBox: View = activity.findViewById(R.id.goalBox)
    private val tvGoalTitle: TextView = activity.findViewById(R.id.tvGoalTitle)
    private val pbGoal: android.widget.ProgressBar = activity.findViewById(R.id.pbGoal)
    private val tvGoalStatus: TextView = activity.findViewById(R.id.tvGoalStatus)
    private val tvPendingTrips: TextView = activity.findViewById(R.id.tvPendingTrips)
    private val tvDayTrips: TextView = activity.findViewById(R.id.tvDayTrips)
    private val tvCoverage: TextView = activity.findViewById(R.id.tvHistCoverage)
    private val cbCommuteDay: CheckBox = activity.findViewById(R.id.cbCommuteDay)
    /** Última página mostrada: o check do dia recalcula o resumo sem recarregar a lista. */
    private var lastPage: RideHistoryPage? = null

    private var period = HistoryPeriod.DAY
    private var reference: LocalDate = LocalDate.now()
    private var purged = false

    /** Corridas na tela agora (atualizadas na hora ao marcar/desmarcar). */
    private var current: RideHistoryPage? = null

    fun bind() {
        periodTabs.forEach { (p, tab) -> tab.setOnClickListener { period = p; refresh() } }
        activity.findViewById<View>(R.id.btnPeriodPrev).setOnClickListener { move(-1) }
        activity.findViewById<View>(R.id.btnPeriodNext).setOnClickListener { move(+1) }
        activity.findViewById<View>(R.id.btnManualRide).setOnClickListener { openManualRide() }
        bindHintToggle()
        tvPendingTrips.setOnClickListener { showPendingTrips() }
        tvDayTrips.setOnClickListener { showDayTrips() }
        activity.findViewById<View>(R.id.btnRideReport).setOnClickListener { chooseReportPeriod() }
    }

    /** "ⓘ Como as corridas entram": um toque mostra a explicação, outro esconde. */
    private fun bindHintToggle() {
        val toggle = activity.findViewById<TextView>(R.id.tvHistHintToggle)
        val hint = activity.findViewById<TextView>(R.id.tvHistHint)
        toggle.setOnClickListener {
            val show = hint.visibility != View.VISIBLE
            hint.visibility = if (show) View.VISIBLE else View.GONE
            toggle.setText(if (show) R.string.hist_hint_hide else R.string.hist_hint_show)
        }
    }

    // ---- Registrar corrida ----

    /**
     * Formulário "Registrar corrida" (corrida que o leitor não gravou, ou particular):
     * app (Particular, Uber ou inDrive), valor, km até o passageiro (opcional), km da viagem
     * e hora. No inDrive a taxa configurada é descontada, como no leitor. O cálculo do card
     * aparece enquanto digita. Data: o dia na tela (filtro Dia) ou hoje (Semana/Mês).
     */
    private fun openManualRide() {
        val day = if (period == HistoryPeriod.DAY) reference else LocalDate.now()
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        fun label(res: Int) = TextView(activity).apply {
            setText(res)
            setTextColor(activity.getColor(R.color.mc_text_muted))
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        fun number(hint: String, decimals: Int) = EditText(activity).apply { this.hint = hint }
            .let { DecimalMask.attach(it, decimals) }
        val etFare = number("25,00", 2)
        val etPickup = number("2,0", 1)
        val etTrip = number("8,0", 1)
        val etTime = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setText(LocalTime.now().format(TIME))
        }
        // Escolha do app: decide a taxa descontada (só o inDrive tem).
        val platforms = listOf(Platform.PARTICULAR, Platform.UBER, Platform.INDRIVE)
        val rgPlatform = android.widget.RadioGroup(activity).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            platforms.forEachIndexed { index, platform ->
                addView(android.widget.RadioButton(activity).apply {
                    id = View.generateViewId()
                    tag = platform
                    text = platform.displayName
                    setTextColor(activity.getColor(R.color.mc_text))
                    buttonTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.mc_yellow))
                    isChecked = index == 0
                })
            }
        }
        fun selectedPlatform(): Platform =
            rgPlatform.findViewById<android.widget.RadioButton>(rgPlatform.checkedRadioButtonId)?.tag as? Platform
                ?: Platform.PARTICULAR
        val tvFeeNote = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.mc_text_muted))
            textSize = 13f
        }
        val tvPreview = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.mc_yellow))
            setPadding(0, (12 * density).toInt(), 0, 0)
            textSize = 15f
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(label(R.string.hist_manual_platform)); addView(rgPlatform); addView(tvFeeNote)
            addView(label(R.string.hist_manual_fare)); addView(etFare)
            addView(label(R.string.hist_manual_pickup)); addView(etPickup)
            addView(label(R.string.hist_manual_trip)); addView(etTrip)
            addView(label(R.string.hist_manual_time)); addView(etTime)
            addView(tvPreview)
        }
        val updatePreview = {
            val platform = selectedPlatform()
            tvFeeNote.text = feeNote(platform)
            tvPreview.text = manualPreview(
                platform,
                NumberParser.parse(etFare.text.toString()),
                NumberParser.parse(etPickup.text.toString()),
                NumberParser.parse(etTrip.text.toString()),
            )
        }
        rgPlatform.setOnCheckedChangeListener { _, _ -> updatePreview() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        listOf(etFare, etPickup, etTrip).forEach { it.addTextChangedListener(watcher) }
        updatePreview()

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.hist_manual_title, day.format(DAY)))
            .setView(android.widget.ScrollView(activity).apply { addView(form) })
            .setPositiveButton(R.string.save) { _, _ ->
                saveManualRide(
                    selectedPlatform(), day, etFare.text.toString(), etPickup.text.toString(),
                    etTrip.text.toString(), etTime.text.toString(),
                )
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** Explica o desconto do app escolhido (inDrive: taxa; Uber: valor já é o ganho). */
    private fun feeNote(platform: Platform): String = when (platform) {
        Platform.INDRIVE -> activity.getString(
            R.string.hist_manual_fee_indrive,
            settings.getInDriveFeePercent().stripTrailingZeros().toPlainString().replace('.', ','),
        )
        Platform.UBER -> activity.getString(R.string.hist_manual_fee_uber)
        else -> activity.getString(R.string.hist_manual_fee_none)
    }

    /** Mesmo cálculo do card, com as regras, a taxa e os custos de agora. */
    private fun manualPreview(platform: Platform, fare: BigDecimal?, pickup: BigDecimal?, trip: BigDecimal?): String {
        if (fare == null || fare.signum() <= 0 || trip == null || trip.signum() <= 0) {
            return activity.getString(R.string.hist_manual_preview_empty)
        }
        return try {
            val analysis = evaluate(
                RideOffer(platform, fare, tripKm = trip, pickupKm = pickup?.takeIf { it.signum() > 0 }),
                RideRules.from(settings).copy(minPassengerRating = null),
            )
            val card = cardFormatter.card(analysis)
            val color = when (analysis.rating) {
                br.com.minhacorrida.core.model.RideRating.GOOD -> "🟢"
                br.com.minhacorrida.core.model.RideRating.LOW_PROFIT -> "🟡"
                br.com.minhacorrida.core.model.RideRating.BELOW_MINIMUM -> "🔴"
            }
            "$color R$/km ${card.valuePerKm} · ${card.profitLabel} R$ ${card.profitValue}\n" +
                "${card.kmDetails.replace('\n', ' ')} · ${card.profitDetails.substringAfter('\n')}"
        } catch (e: Exception) {
            activity.getString(R.string.hist_manual_preview_empty)
        }
    }

    private fun saveManualRide(
        platform: Platform,
        day: LocalDate,
        fareText: String,
        pickupText: String,
        tripText: String,
        timeText: String,
    ) {
        val fare = NumberParser.parse(fareText)
        val trip = NumberParser.parse(tripText)
        val pickup = if (pickupText.isBlank()) null else NumberParser.parse(pickupText)
        val time = runCatching { LocalTime.parse(timeText.trim(), TIME) }.getOrNull()
        if (fare == null || fare.signum() <= 0 || trip == null || trip.signum() <= 0 || time == null ||
            (pickupText.isNotBlank() && (pickup == null || pickup.signum() < 0))
        ) {
            toast(activity.getString(R.string.hist_manual_invalid))
            return
        }
        val millis = day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            io.execute {
                val saved = history.registerManual(fare, pickup?.takeIf { it.signum() > 0 }, trip, millis, platform)
                main.post {
                    toast(
                        if (saved != null) {
                            activity.getString(
                                R.string.hist_manual_saved, platform.displayName,
                                saved.netFare.setScale(2).toPlainString().replace('.', ','),
                            )
                        } else {
                            activity.getString(R.string.hist_manual_invalid)
                        },
                    )
                    refresh()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao registrar corrida manual", e)
        }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    /** Recarrega o período atual (na 1ª vez, apaga também o que passou de 60 dias). */
    fun refresh() {
        styleTabs()
        try {
            io.execute {
                if (!purged) {
                    history.purgeOld()
                    purged = true
                }
                val page = history.page(period, reference)
                main.post { if (!activity.isFinishing && !activity.isDestroyed) render(page) }
            }
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao carregar o histórico", e)
        }
    }

    private fun move(steps: Long) {
        reference = period.shift(reference, steps)
        refresh()
    }

    private fun render(page: RideHistoryPage) {
        current = page
        tvPeriodLabel.text = formatter.periodLabel(page, LocalDate.now())
        renderSummary(page)
        list.removeAllViews()
        page.rides.forEach { list.addView(rowView(it, page.period)) }
        tvEmpty.visibility = if (page.rides.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Resumo do período: corridas e km, lucro líquido, o total a "Separar" (combustível +
     * manutenção + deduções como o dízimo) item por item e, no filtro Dia, a meta diária.
     * Atualiza na hora ao marcar/desmarcar corridas.
     */
    private fun renderSummary(page: RideHistoryPage) {
        lastPage = page
        renderPendingTrips()
        renderDayTrips(page)
        tvCountKm.text = formatter.countAndKm(page)
        val period = periodCommute(page)
        val commute = period.included
        renderCommuteCheck(page, period)
        val progress = try {
            val goal = if (page.period == HistoryPeriod.DAY) goals.getDailyGoal() else null
            // Gasolina do deslocamento sem passageiro também sai do lucro (antes do dízimo).
            br.com.minhacorrida.core.goal.DailyGoalCalculator.progress(
                page.summary.netProfit - (commute?.fuelCost ?: BigDecimal.ZERO), goals.getProfitDeductions(), goal,
            )
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao calcular deduções/meta", e)
            br.com.minhacorrida.core.goal.DailyGoalCalculator.progress(page.summary.netProfit, emptyList(), null)
        }
        tvProfit.text = formatter.kept(progress)
        tvCost.text = formatter.setAside(page, progress, commute)
        tvCostDetail.text = formatter.setAsideDetails(page, progress, commute, period.excluded)
        // Mesmo sem bater a meta: as corridas pagaram os custos delas e a gasolina do período?
        val coverage = formatter.coverage(
            page, br.com.minhacorrida.core.km.DailyCommuteUseCase.sum(listOfNotNull(period.included, period.excluded)),
        )
        tvCoverage.text = coverage.orEmpty()
        tvCoverage.visibility = if (coverage == null) View.GONE else View.VISIBLE
        renderGoal(progress)
    }

    /**
     * Deslocamento de cada dia com corrida realizada (GPS do dia ou média do painel), separado
     * pelos checks: marcados entram nas contas; desmarcados só aparecem para saber o valor.
     */
    private fun periodCommute(page: RideHistoryPage): br.com.minhacorrida.core.km.PeriodCommute {
        val zone = ZoneId.systemDefault()
        val ridesKmByDay = page.rides.filter { it.done }
            .groupBy { java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { acc, r -> acc + r.totalKm } }
        return dailyCommute.forPeriod(ridesKmByDay) { from, to -> history.doneKmBetween(from, to) }
    }

    /** Check "Descontar o deslocamento deste dia": só no filtro Dia e com deslocamento calculado. */
    private fun renderCommuteCheck(page: RideHistoryPage, period: br.com.minhacorrida.core.km.PeriodCommute) {
        val entry = period.days[page.range.start]
        val show = page.period == HistoryPeriod.DAY && entry != null
        cbCommuteDay.setOnCheckedChangeListener(null)
        cbCommuteDay.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        cbCommuteDay.isChecked = entry!!.second
        cbCommuteDay.text = activity.getString(
            R.string.commute_check, entry.first.commuteKm.toPlainString().replace('.', ','),
            entry.first.fuelCost.setScale(2).toPlainString().replace('.', ','),
        )
        cbCommuteDay.setOnCheckedChangeListener { _, checked ->
            try {
                commuteChoices.setIncluded(page.range.start, checked)
                lastPage?.let(::renderSummary)
            } catch (e: Exception) {
                android.util.Log.e("MC/RideHistoryScreen", "Falha ao marcar o deslocamento do dia", e)
            }
        }
    }

    // ---- Relatório .md do período ----

    /** Esta semana, este mês, mês passado ou um período escolhido no calendário. */
    private fun chooseReportPeriod() {
        val today = LocalDate.now()
        val options = arrayOf(
            activity.getString(R.string.report_week),
            activity.getString(R.string.report_month),
            activity.getString(R.string.report_last_month),
            activity.getString(R.string.report_custom),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.report_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportReport(HistoryPeriod.WEEK.rangeOf(today))
                    1 -> exportReport(HistoryPeriod.MONTH.rangeOf(today))
                    2 -> exportReport(HistoryPeriod.MONTH.rangeOf(today.withDayOfMonth(1).minusDays(1)))
                    else -> pickCustomRange(today)
                }
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** Dois calendários: data inicial e data final (incluída). */
    private fun pickCustomRange(today: LocalDate) {
        android.app.DatePickerDialog(activity, { _, y, m, d ->
            val start = LocalDate.of(y, m + 1, d)
            android.app.DatePickerDialog(activity, { _, y2, m2, d2 ->
                val end = LocalDate.of(y2, m2 + 1, d2)
                if (end < start) {
                    toast(activity.getString(R.string.report_invalid_range))
                } else {
                    exportReport(DateRange(start, end.plusDays(1)))
                }
            }, today.year, today.monthValue - 1, today.dayOfMonth).apply {
                setTitle(R.string.report_end)
                datePicker.minDate = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.show()
        }, today.year, today.monthValue - 1, 1).apply { setTitle(R.string.report_start) }.show()
    }

    /** Gera o .md fora da thread principal e abre o Compartilhar (ex.: anexar no Claude). */
    private fun exportReport(range: DateRange) {
        toast(activity.getString(R.string.report_generating))
        try {
            io.execute {
                val markdown = report.markdown(range, appVersion, System.currentTimeMillis())
                val name = "minha-corrida-relatorio-${range.start}-a-${range.endInclusive}.md"
                main.post {
                    try {
                        MarkdownSharer.share(activity, name, markdown, activity.getString(R.string.report_title))
                    } catch (e: Exception) {
                        android.util.Log.e("MC/RideHistoryScreen", "Falha ao compartilhar relatório", e)
                        toast(activity.getString(R.string.report_failed))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao gerar relatório", e)
            toast(activity.getString(R.string.report_failed))
        }
    }

    // ---- Trajetos pendentes (sensor de veículo) ----

    private fun renderPendingTrips() {
        val count = trips.pending().size
        tvPendingTrips.visibility = if (count > 0) View.VISIBLE else View.GONE
        tvPendingTrips.text = activity.getString(R.string.trip_pending_banner, count)
    }

    /** Filtro Dia: "🚗 Trajetos do dia: 6 · 49,2 km registrados · toque para ver". */
    private fun renderDayTrips(page: RideHistoryPage) {
        try {
            val text = if (page.period == HistoryPeriod.DAY) {
                br.com.minhacorrida.core.km.TripListFormatter.summary(trips.onDay(page.range.start))
            } else {
                null
            }
            tvDayTrips.visibility = if (text == null) View.GONE else View.VISIBLE
            tvDayTrips.text = text.orEmpty()
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao mostrar os trajetos do dia", e)
            tvDayTrips.visibility = View.GONE
        }
    }

    /**
     * Lista dos trajetos do dia (✅ registrado, ❌ descartado, ⏳ pendente). Tocar num deles
     * permite Registrar ou Descartar — também para trocar uma decisão já tomada.
     */
    private fun showDayTrips() {
        val day = lastPage?.range?.start ?: return
        val zone = ZoneId.systemDefault()
        val list = trips.onDay(day)
        if (list.isEmpty()) {
            renderDayTrips(lastPage ?: return)
            return
        }
        // Km das corridas realizadas (app) × km do GPS dos trajetos em que elas aconteceram.
        val rides = lastPage?.rides.orEmpty().filter { it.done }.map { it.timestampMillis to it.totalKm }
        val check = br.com.minhacorrida.core.km.RideKmComparison.check(list, rides)
        val labels = list.map { br.com.minhacorrida.core.km.TripListFormatter.row(it, zone, check.perTrip[it.id]) }
        val comparison = br.com.minhacorrida.core.km.RideKmComparison.summary(check)
        val footer = TextView(activity).apply {
            text = listOfNotNull(comparison, activity.getString(R.string.trip_day_footer)).joinToString("\n\n")
            setTextColor(activity.getColor(R.color.mc_text_muted))
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.trip_day_title, day.format(DateTimeFormatter.ofPattern("dd/MM"))))
            .setItems(labels.toTypedArray()) { _, which -> changeTrip(list[which].id, labels[which]) }
            .setView(footer)
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** Registrar/Descartar um trajeto do dia (pendente ou já decidido); os km do dia acompanham. */
    private fun changeTrip(id: String, label: String) {
        fun apply(status: br.com.minhacorrida.core.km.TripStatus) {
            try {
                io.execute {
                    trips.change(id, status)
                    if (status == br.com.minhacorrida.core.km.TripStatus.DISCARDED) parking.revertIfFrom(id)
                    activity.getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
                    main.post { refresh() }
                }
            } catch (e: Exception) {
                android.util.Log.e("MC/RideHistoryScreen", "Falha ao trocar a decisão do trajeto", e)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(label)
            .setMessage(R.string.trip_change_message)
            .setPositiveButton(R.string.trip_keep) { _, _ -> apply(br.com.minhacorrida.core.km.TripStatus.KEPT) }
            .setNeutralButton(R.string.trip_discard) { _, _ -> apply(br.com.minhacorrida.core.km.TripStatus.DISCARDED) }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** Lista dos trajetos pendentes; tocar num deles pergunta Registrar ou Descartar. */
    private fun showPendingTrips() {
        val pending = trips.pending()
        if (pending.isEmpty()) {
            renderPendingTrips()
            return
        }
        val day = DateTimeFormatter.ofPattern("dd/MM")
        val zone = ZoneId.systemDefault()
        val labels = pending.map { t ->
            val start = java.time.Instant.ofEpochMilli(t.startMillis).atZone(zone)
            val end = java.time.Instant.ofEpochMilli(t.endMillis).atZone(zone)
            activity.getString(
                R.string.trip_row, t.km.toPlainString().replace('.', ','), start.format(day), start.format(TIME), end.format(TIME),
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.trip_pending_title)
            .setItems(labels.toTypedArray()) { _, which -> decideTrip(pending[which].id, labels[which]) }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    private fun decideTrip(id: String, label: String) {
        fun apply(keep: Boolean) {
            try {
                io.execute {
                    if (keep) {
                        trips.keep(id)
                    } else {
                        trips.discard(id)
                        parking.revertIfFrom(id)
                    }
                    activity.getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
                    main.post { refresh() }
                }
            } catch (e: Exception) {
                android.util.Log.e("MC/RideHistoryScreen", "Falha ao decidir o trajeto", e)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(label)
            .setMessage(R.string.trip_decide_message)
            .setPositiveButton(R.string.trip_keep) { _, _ -> apply(true) }
            .setNeutralButton(R.string.trip_discard) { _, _ -> apply(false) }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** Meta diária (só no filtro Dia): barra de progresso e quanto falta (no bolso e em lucro líquido). */
    private fun renderGoal(progress: br.com.minhacorrida.core.goal.GoalProgress) {
        try {
            val text = goalFormatter.format(progress)
            if (text.title == null) {
                goalBox.visibility = View.GONE
                return
            }
            goalBox.visibility = View.VISIBLE
            tvGoalTitle.text = text.title
            pbGoal.progress = text.progressPercent ?: 0
            tvGoalStatus.text = text.status
            tvGoalStatus.setTextColor(activity.getColor(if (text.reached) R.color.mc_green else R.color.mc_text))
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao mostrar a meta", e)
            goalBox.visibility = View.GONE
        }
    }

    /**
     * Corrida guardada no mesmo card da oferta: faixa do app (valor, "era R$" e hora) e os
     * 3 quadrados R$/km | Lucro | Mín. verde, na cor da corrida. Embaixo, se foi realizada.
     * Toque marca/desmarca "realizada"; toque longo corrige o valor.
     */
    private fun rowView(record: RideRecord, period: HistoryPeriod): View {
        val card = activity.layoutInflater.inflate(R.layout.overlay_ride, list, false)
        try {
            val text = formatter.card(record, period, settings.getMinValuePerKm(), settings.getMinNetProfit())
            cardBinder.bindTexts(card, text, record.platform, record.rating)
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao montar o card da corrida ${record.id}", e)
        }
        val density = activity.resources.displayMetrics.density
        (card.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )).also { params ->
            params.bottomMargin = (10 * density).toInt()
            card.layoutParams = params
        }
        val status = card.findViewById<TextView>(R.id.tvTapToClose).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        var done = record.done
        val showStatus = {
            status.text = activity.getString(if (done) R.string.hist_card_done else R.string.hist_card_not_done)
            status.setTextColor(if (done) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
            card.alpha = if (done) 1f else NOT_DONE_ALPHA
        }
        showStatus()
        card.setOnClickListener {
            done = !done
            showStatus()
            onDoneChanged(record.id, done)
        }
        card.setOnLongClickListener { showRideMenu(record); true }
        return card
    }

    /** Toque longo: corrigir o valor (contraproposta aceita, valor digitado no lápis etc.). */
    // ---- Toque longo: corrigir valor, corrigir km ou excluir ----

    /** Menu do toque longo no card da corrida. */
    private fun showRideMenu(record: RideRecord) {
        val title = activity.getString(
            R.string.hist_menu_title, record.platform.displayName, money(record.fare), TIME.format(at(record)),
        )
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(
                arrayOf(
                    activity.getString(R.string.hist_menu_fare),
                    activity.getString(R.string.hist_menu_km),
                    activity.getString(R.string.hist_menu_delete),
                ),
            ) { _, which ->
                when (which) {
                    0 -> askNewFare(record)
                    1 -> askNewKm(record)
                    else -> confirmDelete(record, title)
                }
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** "Corrigir km": km até o passageiro (opcional) e km da viagem. */
    private fun askNewKm(record: RideRecord) {
        val density = activity.resources.displayMetrics.density
        fun field(hintRes: Int, value: BigDecimal?) = DecimalMask.attach(
            EditText(activity).apply { hint = activity.getString(hintRes) }, 1,
        ).also { DecimalMask.set(it, value) }
        val etPickup = field(R.string.hist_manual_pickup, record.pickupKm)
        val etTrip = field(R.string.hist_manual_trip, record.tripKm)
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(TextView(activity).apply { setText(R.string.hist_manual_pickup) })
            addView(etPickup)
            addView(TextView(activity).apply { setText(R.string.hist_manual_trip) })
            addView(etTrip)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.hist_menu_km)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                val pickup = etPickup.text.toString().takeIf { it.isNotBlank() }?.let(NumberParser::parse)
                val trip = NumberParser.parse(etTrip.text.toString())
                if (trip == null || trip.signum() <= 0 || (etPickup.text.isNotBlank() && pickup == null)) {
                    Toast.makeText(activity, R.string.invalid_value, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                try {
                    io.execute {
                        val saved = history.changeKm(record.id, pickup, trip)
                        main.post {
                            if (saved == null) Toast.makeText(activity, R.string.invalid_value, Toast.LENGTH_LONG).show()
                            refresh()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MC/RideHistoryScreen", "Falha ao corrigir km", e)
                }
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** "Excluir corrida": confirma mostrando qual corrida sai. */
    private fun confirmDelete(record: RideRecord, title: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.hist_delete_title)
            .setMessage(activity.getString(R.string.hist_delete_message, title))
            .setPositiveButton(R.string.hist_delete_confirm) { _, _ ->
                try {
                    io.execute {
                        val deleted = history.delete(record.id)
                        main.post {
                            if (deleted) toast(activity.getString(R.string.hist_deleted))
                            refresh()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MC/RideHistoryScreen", "Falha ao excluir corrida", e)
                }
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    private fun at(record: RideRecord) = java.time.Instant.ofEpochMilli(record.timestampMillis).atZone(java.time.ZoneId.systemDefault())
    private fun money(value: BigDecimal) = value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    /**
     * "Corrigir valor": campo vazio (o valor atual aparece no fundo), teclado já aberto.
     * Digita "2550" ou "25,50" → R$ 25,50. Salvar com o campo vazio não muda nada.
     */
    private fun askNewFare(record: RideRecord) {
        val density = activity.resources.displayMetrics.density
        val input = DecimalMask.attach(EditText(activity).apply { hint = money(record.fare) }, 2)
        val form = LinearLayout(activity).apply {
            val pad = (20 * density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.hist_edit_title)
            .setMessage(activity.getString(R.string.hist_edit_message, record.platform.displayName, money(record.fare)))
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) saveNewFare(record.id, text)
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun saveNewFare(id: String, text: String) {
        val fare = NumberParser.parse(text)
        if (fare == null || fare.signum() <= 0) {
            Toast.makeText(activity, R.string.invalid_value, Toast.LENGTH_LONG).show()
            return
        }
        try {
            io.execute {
                history.changeFare(id, fare)
                main.post { refresh() }
            }
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao corrigir valor", e)
        }
    }

    /** Atualiza o resumo na hora e grava a marcação em segundo plano. */
    private fun onDoneChanged(id: String, done: Boolean) {
        val page = current ?: return
        val rides = page.rides.map { if (it.id == id) it.copy(done = done) else it }
        val updated = page.copy(rides = rides, summary = RideHistoryUseCase.summarize(rides))
        current = updated
        renderSummary(updated)
        try {
            io.execute { history.setDone(id, done) }
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao gravar marcação", e)
        }
    }

    private companion object {
        /** Corrida ainda não marcada como realizada: card mais apagado. */
        const val NOT_DONE_ALPHA = 0.6f
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
    }

    private fun styleTabs() {
        periodTabs.forEach { (p, tab) ->
            val selected = p == period
            tab.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
            tab.setTextColor(activity.getColor(if (selected) R.color.mc_black else R.color.mc_yellow))
        }
    }
}
