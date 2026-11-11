package br.com.minhacorrida.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import br.com.minhacorrida.R
import br.com.minhacorrida.core.km.FuelEntry
import br.com.minhacorrida.core.km.FuelLogRepository
import br.com.minhacorrida.core.km.FuelStatsCalculator
import br.com.minhacorrida.core.km.KmLogRepository
import br.com.minhacorrida.core.km.KmSettingsRepository
import br.com.minhacorrida.core.parser.NumberParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Seção "Km rodados e abastecimentos" das Configurações:
 * - liga/desliga o controle de km (quem chama cuida da permissão e do serviço do GPS);
 * - km de hoje e consumo real pelo tanque cheio, com "Usar nos custos";
 * - abastecimentos: "+ Registrar abastecimento", toque edita (data, km do painel, litros,
 *   valor, tanque cheio) e dentro da edição dá para excluir.
 */
class KmControlSection(
    private val activity: Activity,
    private val settings: KmSettingsRepository,
    private val kmLog: KmLogRepository,
    private val fuelLog: FuelLogRepository,
    /** Motorista ligou/desligou: pede permissão e liga/desliga o serviço. */
    private val onToggle: (Boolean) -> Unit,
    /** "Usar nos custos": preço médio do litro e km/L real para os campos de custos. */
    private val onUseStats: (pricePerLiter: BigDecimal, kmPerLiter: BigDecimal) -> Unit,
) {
    private val cbEnabled: CheckBox = activity.findViewById(R.id.cbKmTracking)
    private val tvToday: TextView = activity.findViewById(R.id.tvKmToday)
    private val tvStats: TextView = activity.findViewById(R.id.tvFuelStats)
    private val btnUseStats: Button = activity.findViewById(R.id.btnUseFuelStats)
    private val list: LinearLayout = activity.findViewById(R.id.fuelList)

    fun bind() {
        cbEnabled.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        activity.findViewById<Button>(R.id.btnAddFuel).setOnClickListener { openEditor(null) }
        refresh()
    }

    /** Marca/desmarca sem disparar [onToggle] (ex.: permissão negada). */
    fun setChecked(checked: Boolean) {
        cbEnabled.setOnCheckedChangeListener(null)
        cbEnabled.isChecked = checked
        cbEnabled.setOnCheckedChangeListener { _, c -> onToggle(c) }
    }

    fun refresh() {
        try {
            setChecked(settings.isKmTrackingEnabled())
            tvToday.text = activity.getString(R.string.km_today, km(kmLog.kmOn(LocalDate.now())))
            val entries = fuelLog.all()
            renderStats(entries)
            renderList(entries)
        } catch (e: Exception) {
            android.util.Log.e("MC/KmControl", "Falha ao mostrar o controle de km", e)
        }
    }

    private fun renderStats(entries: List<FuelEntry>) {
        val stats = FuelStatsCalculator.of(entries)
        val kmPerLiter = stats.kmPerLiter
        val price = stats.pricePerLiter
        tvStats.text = when {
            kmPerLiter != null && price != null -> {
                val gps = kmLog.kmBetween(stats.from!!, stats.to!!)
                activity.getString(R.string.fuel_stats, decimal(kmPerLiter), money(price), km(stats.odometerKm!!), km(gps))
            }
            else -> activity.getString(R.string.fuel_stats_empty)
        }
        btnUseStats.visibility = if (kmPerLiter != null && price != null) View.VISIBLE else View.GONE
        btnUseStats.setOnClickListener {
            if (kmPerLiter != null && price != null) onUseStats(price, kmPerLiter)
        }
    }

    private fun renderList(entries: List<FuelEntry>) {
        list.removeAllViews()
        entries.take(MAX_LISTED).forEach { entry ->
            list.addView(TextView(activity).apply {
                text = activity.getString(
                    R.string.fuel_row,
                    entry.date.format(DAY), km(entry.odometerKm), decimal(entry.liters), money(entry.totalPrice),
                    money(entry.pricePerLiter),
                    activity.getString(if (entry.fullTank) R.string.fuel_full else R.string.fuel_partial),
                )
                setTextColor(activity.getColor(R.color.mc_text))
                textSize = 14f
                val pad = (8 * activity.resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
                setOnClickListener { openEditor(entry) }
            })
        }
    }

    /** Novo abastecimento (entry null) ou edição: data, km do painel, litros, valor, tanque cheio. */
    private fun openEditor(entry: FuelEntry?) {
        val density = activity.resources.displayMetrics.density
        var date = entry?.date ?: LocalDate.now()
        fun number(hintRes: Int, value: BigDecimal?) = EditText(activity).apply {
            hint = activity.getString(hintRes)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            value?.let { setText(it.stripTrailingZeros().toPlainString().replace('.', ',')) }
        }
        val btnDate = Button(activity).apply { text = activity.getString(R.string.fuel_date, date.format(DAY_YEAR)) }
        btnDate.setOnClickListener {
            DatePickerDialog(activity, { _, y, m, d ->
                date = LocalDate.of(y, m + 1, d)
                btnDate.text = activity.getString(R.string.fuel_date, date.format(DAY_YEAR))
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        val etOdo = number(R.string.fuel_odometer, entry?.odometerKm)
        val etLiters = number(R.string.fuel_liters, entry?.liters)
        val etPrice = number(R.string.fuel_price, entry?.totalPrice)
        val cbFull = CheckBox(activity).apply {
            text = activity.getString(R.string.fuel_full_tank)
            isChecked = entry?.fullTank ?: true
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(btnDate)
            addView(TextView(activity).apply { setText(R.string.fuel_odometer) }); addView(etOdo)
            addView(TextView(activity).apply { setText(R.string.fuel_liters) }); addView(etLiters)
            addView(TextView(activity).apply { setText(R.string.fuel_price) }); addView(etPrice)
            addView(cbFull)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (entry == null) R.string.fuel_new else R.string.fuel_edit)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                val saved = runCatching {
                    FuelEntry(
                        id = entry?.id ?: UUID.randomUUID().toString(),
                        date = date,
                        odometerKm = NumberParser.parse(etOdo.text.toString())!!,
                        liters = NumberParser.parse(etLiters.text.toString())!!,
                        totalPrice = NumberParser.parse(etPrice.text.toString())!!,
                        fullTank = cbFull.isChecked,
                    )
                }.getOrNull()
                if (saved == null) {
                    Toast.makeText(activity, R.string.fuel_invalid, Toast.LENGTH_LONG).show()
                } else {
                    fuelLog.save(saved)
                    refresh()
                }
            }
            .setNegativeButton(R.string.diagnostics_close, null)
        if (entry != null) {
            dialog.setNeutralButton(R.string.hist_delete_confirm) { _, _ ->
                AlertDialog.Builder(activity)
                    .setMessage(R.string.fuel_delete_message)
                    .setPositiveButton(R.string.hist_delete_confirm) { _, _ -> fuelLog.delete(entry.id); refresh() }
                    .setNegativeButton(R.string.diagnostics_close, null)
                    .show()
            }
        }
        dialog.show()
    }

    private fun km(value: BigDecimal) = value.stripTrailingZeros().let {
        java.text.DecimalFormat("#,##0.#", java.text.DecimalFormatSymbols(java.util.Locale("pt", "BR"))).format(it)
    }
    private fun decimal(value: BigDecimal) = value.stripTrailingZeros().toPlainString().replace('.', ',')
    private fun money(value: BigDecimal) =
        java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(java.util.Locale("pt", "BR"))).format(value)

    private companion object {
        const val MAX_LISTED = 10
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
        val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
