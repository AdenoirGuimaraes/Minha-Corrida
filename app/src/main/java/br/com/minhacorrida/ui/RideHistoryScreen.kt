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
import br.com.minhacorrida.core.history.HistoryPeriod
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
        activity.findViewById<View>(R.id.btnKeepSelected).setOnClickListener { confirmKeepSelected() }
        bindHintToggle()
    }

    // ---- Corridas Selecionadas ----

    /** Confirma antes de apagar: mostra quantas saem, quantas ficam e de qual período. */
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

    private fun confirmKeepSelected() {
        val page = current ?: return
        val notDone = page.rides.count { !it.done }
        if (notDone == 0) {
            toast(activity.getString(R.string.hist_keep_none))
            return
        }
        val done = page.rides.size - notDone
        AlertDialog.Builder(activity)
            .setTitle(R.string.hist_keep_title)
            .setMessage(
                activity.getString(R.string.hist_keep_message, notDone, formatter.periodLabel(page, LocalDate.now()), done),
            )
            .setPositiveButton(R.string.hist_keep_confirm) { _, _ -> keepSelected(page.period, reference) }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    private fun keepSelected(period: HistoryPeriod, ref: LocalDate) {
        try {
            io.execute {
                val removed = history.keepOnlyDone(period, ref)
                main.post {
                    toast(activity.getString(R.string.hist_keep_done, removed))
                    refresh()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao apagar não marcadas", e)
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
        fun number(hint: String) = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            this.hint = hint
        }
        val etFare = number("25,00")
        val etPickup = number("2,0")
        val etTrip = number("8,0")
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
                RideRules(
                    settings.getMinValuePerKm(),
                    operatingCost = settings.getOperatingCost(),
                    inDriveFeePercent = settings.getInDriveFeePercent(),
                    minNetProfit = settings.getMinNetProfit(),
                ),
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
        tvCountKm.text = formatter.countAndKm(page)
        tvProfit.text = formatter.profit(page)
        val progress = try {
            val goal = if (page.period == HistoryPeriod.DAY) goals.getDailyGoal() else null
            br.com.minhacorrida.core.goal.DailyGoalCalculator.progress(
                page.summary.netProfit, goals.getProfitDeductions(), goal,
            )
        } catch (e: Exception) {
            android.util.Log.e("MC/RideHistoryScreen", "Falha ao calcular deduções/meta", e)
            br.com.minhacorrida.core.goal.DailyGoalCalculator.progress(page.summary.netProfit, emptyList(), null)
        }
        tvCost.text = formatter.setAside(page, progress)
        tvCostDetail.text = formatter.setAsideDetails(page, progress)
        renderGoal(progress)
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
        card.setOnLongClickListener { askNewFare(record); true }
        return card
    }

    /** Toque longo: corrigir o valor (contraproposta aceita, valor digitado no lápis etc.). */
    private fun askNewFare(record: RideRecord) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(record.fare.toPlainString().replace('.', ','))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.hist_edit_title)
            .setMessage(activity.getString(R.string.hist_edit_message, record.platform.displayName))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> saveNewFare(record.id, input.text.toString()) }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
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
