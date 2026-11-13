package br.com.minhacorrida.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import br.com.minhacorrida.R
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.receipt.MaintenanceEntry
import br.com.minhacorrida.core.receipt.MaintenanceLogRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * "🔧 Manutenções" nas Configurações: data, descrição, valor e foto do comprovante (nota da
 * oficina). Toque edita; dentro da edição dá para excluir. Entra no relatório e no backup.
 */
class MaintenanceSection(
    private val activity: Activity,
    private val log: MaintenanceLogRepository,
    private val receipts: ReceiptPicker,
    /** Avisado quando a lista muda (ex.: limpar fotos sem registro). */
    private val onChanged: () -> Unit,
) {
    private val list: LinearLayout = activity.findViewById(R.id.maintenanceList)

    fun bind() {
        activity.findViewById<Button>(R.id.btnAddMaintenance).setOnClickListener { openEditor(null) }
        refresh()
    }

    fun refresh() {
        try {
            list.removeAllViews()
            log.all().take(MAX_LISTED).forEach { entry ->
                list.addView(TextView(activity).apply {
                    val photo = if (receipts.label(entry.id) == activity.getString(R.string.receipt_attached)) " · 🧾" else ""
                    text = activity.getString(R.string.maintenance_row, entry.date.format(DAY), entry.description, money(entry.value)) + photo
                    setTextColor(activity.getColor(R.color.mc_text))
                    textSize = 14f
                    val pad = (8 * activity.resources.displayMetrics.density).toInt()
                    setPadding(0, pad, 0, pad)
                    setOnClickListener { openEditor(entry) }
                })
            }
        } catch (e: Exception) {
            android.util.Log.e("MC/Maintenance", "Falha ao mostrar manutenções", e)
        }
    }

    private fun openEditor(entry: MaintenanceEntry?) {
        val density = activity.resources.displayMetrics.density
        var date = entry?.date ?: LocalDate.now()
        val id = entry?.id ?: UUID.randomUUID().toString()
        val btnDate = Button(activity).apply { text = activity.getString(R.string.fuel_date, date.format(DAY_YEAR)) }
        btnDate.setOnClickListener {
            DatePickerDialog(activity, { _, y, m, d ->
                date = LocalDate.of(y, m + 1, d)
                btnDate.text = activity.getString(R.string.fuel_date, date.format(DAY_YEAR))
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        val etDescription = EditText(activity).apply {
            hint = activity.getString(R.string.maintenance_description_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            entry?.let { setText(it.description) }
        }
        val etValue = DecimalMask.attach(EditText(activity), 2).also { DecimalMask.set(it, entry?.value) }
        val btnReceipt = Button(activity).apply { text = receipts.label(id) }
        btnReceipt.setOnClickListener {
            receipts.choose(id) {
                btnReceipt.text = receipts.label(id)
                refresh()
            }
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(btnDate)
            addView(TextView(activity).apply { setText(R.string.maintenance_description) }); addView(etDescription)
            addView(TextView(activity).apply { setText(R.string.maintenance_value) }); addView(etValue)
            addView(btnReceipt)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (entry == null) R.string.maintenance_new else R.string.maintenance_edit)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                val saved = runCatching {
                    MaintenanceEntry(id, date, etDescription.text.toString().trim(), NumberParser.parse(etValue.text.toString())!!)
                }.getOrNull()
                if (saved == null) {
                    Toast.makeText(activity, R.string.maintenance_invalid, Toast.LENGTH_LONG).show()
                } else {
                    log.save(saved)
                    refresh()
                    onChanged()
                }
            }
            .setNegativeButton(R.string.diagnostics_close, null)
        if (entry != null) {
            dialog.setNeutralButton(R.string.hist_delete_confirm) { _, _ ->
                AlertDialog.Builder(activity)
                    .setMessage(R.string.maintenance_delete_message)
                    .setPositiveButton(R.string.hist_delete_confirm) { _, _ ->
                        log.delete(entry.id)
                        receipts.remove(entry.id)
                        refresh()
                        onChanged()
                    }
                    .setNegativeButton(R.string.diagnostics_close, null)
                    .show()
            }
        }
        dialog.show()
    }

    private fun money(value: java.math.BigDecimal) =
        java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(java.util.Locale("pt", "BR"))).format(value)

    private companion object {
        const val MAX_LISTED = 10
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
        val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
