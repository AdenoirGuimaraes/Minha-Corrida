package br.com.minhacorrida.ui

import android.app.Activity
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
) {
    private val main = Handler(Looper.getMainLooper())
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

    private var period = HistoryPeriod.DAY
    private var reference: LocalDate = LocalDate.now()
    private var purged = false

    /** Corridas na tela agora (atualizadas na hora ao marcar/desmarcar). */
    private var current: RideHistoryPage? = null

    fun bind() {
        periodTabs.forEach { (p, tab) -> tab.setOnClickListener { period = p; refresh() } }
        activity.findViewById<View>(R.id.btnPeriodPrev).setOnClickListener { move(-1) }
        activity.findViewById<View>(R.id.btnPeriodNext).setOnClickListener { move(+1) }
    }

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

    private fun renderSummary(page: RideHistoryPage) {
        tvCountKm.text = formatter.countAndKm(page)
        tvProfit.text = formatter.profit(page)
        tvCost.text = formatter.cost(page)
        tvCostDetail.text = formatter.costDetails(page)
    }

    /** Linha com check: verde = realizei, vermelho = não realizei. */
    private fun rowView(record: RideRecord, period: HistoryPeriod) = CheckBox(activity).apply {
        text = formatter.row(record, period)
        isChecked = record.done
        buttonTintList = activity.getColorStateList(R.color.check_tint)
        setTextColor(activity.getColorStateList(R.color.check_tint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val pad = (6 * resources.displayMetrics.density).toInt()
        setPadding(paddingLeft, pad, paddingRight, pad)
        setOnCheckedChangeListener { _, checked -> onDoneChanged(record.id, checked) }
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

    private fun styleTabs() {
        periodTabs.forEach { (p, tab) ->
            val selected = p == period
            tab.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
            tab.setTextColor(activity.getColor(if (selected) R.color.mc_black else R.color.mc_yellow))
        }
    }
}
