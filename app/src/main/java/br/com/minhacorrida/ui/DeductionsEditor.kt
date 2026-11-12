package br.com.minhacorrida.ui

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import br.com.minhacorrida.R
import br.com.minhacorrida.core.goal.DailyGoalCalculator
import br.com.minhacorrida.core.goal.ProfitDeduction
import br.com.minhacorrida.core.parser.NumberParser

/**
 * Lista editável de deduções do lucro líquido nas Configurações: cada linha tem
 * descrição, porcentagem e ✕ para remover; o botão "+ Adicionar dedução" cria outra.
 * Só monta e lê os campos — gravar é com quem chama (MainActivity.saveSettings).
 */
class DeductionsEditor(
    private val activity: Activity,
    private val container: LinearLayout,
    addButton: Button,
) {
    private class Row(val view: LinearLayout, val description: EditText, val percent: EditText)

    private val rows = mutableListOf<Row>()

    init {
        addButton.setOnClickListener { addRow(null).description.requestFocus() }
    }

    /** Mostra as deduções gravadas (uma linha vazia quando não há nenhuma). */
    fun show(deductions: List<ProfitDeduction>) {
        container.removeAllViews()
        rows.clear()
        deductions.forEach { addRow(it) }
        if (rows.isEmpty()) addRow(null)
    }

    /**
     * Lê as linhas. Linhas totalmente vazias são ignoradas.
     * @return as deduções, ou null se alguma porcentagem for inválida ou a soma chegar a 100%.
     */
    fun read(): List<ProfitDeduction>? {
        val result = mutableListOf<ProfitDeduction>()
        for (row in rows) {
            val description = row.description.text.toString().trim()
            val percentText = row.percent.text.toString().trim()
            if (description.isEmpty() && percentText.isEmpty()) continue
            val percent = NumberParser.parse(percentText) ?: return null
            result += runCatching { ProfitDeduction(description, percent) }.getOrNull() ?: return null
        }
        return result.takeIf { DailyGoalCalculator.isValid(it) }
    }

    private fun addRow(deduction: ProfitDeduction?): Row {
        val density = activity.resources.displayMetrics.density
        val description = EditText(activity).apply {
            hint = activity.getString(R.string.deduction_description_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(activity.getColor(R.color.mc_text))
            setHintTextColor(activity.getColor(R.color.mc_text_muted))
            setSingleLine()
            deduction?.let { setText(it.description) }
        }
        // Vírgula automática com 1 casa: digita "100" → "10,0".
        val percent = DecimalMask.attach(
            EditText(activity).apply {
                hint = activity.getString(R.string.deduction_percent_hint)
                setTextColor(activity.getColor(R.color.mc_text))
                setHintTextColor(activity.getColor(R.color.mc_text_muted))
                gravity = Gravity.CENTER
            },
            1,
        ).also { field -> deduction?.let { DecimalMask.set(field, it.percent) } }
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val row = Row(line, description, percent)
        val remove = Button(activity).apply {
            text = "✕"
            contentDescription = activity.getString(R.string.deduction_remove)
            setTextColor(activity.getColor(R.color.mc_yellow))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                container.removeView(line)
                rows.remove(row)
                if (rows.isEmpty()) addRow(null)
            }
        }
        line.addView(description, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(percent, LinearLayout.LayoutParams((72 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        line.addView(remove, LinearLayout.LayoutParams((48 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(line)
        rows += row
        return row
    }
}
