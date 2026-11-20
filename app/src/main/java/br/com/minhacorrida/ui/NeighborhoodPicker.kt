package br.com.minhacorrida.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.com.minhacorrida.R
import br.com.minhacorrida.core.destination.DestinationAdvisor
import br.com.minhacorrida.core.neighborhood.Neighborhood
import br.com.minhacorrida.core.neighborhood.NeighborhoodMark
import br.com.minhacorrida.core.neighborhood.NeighborhoodRepository

/**
 * Tela cheia "Bairros de <cidade>": busca, filtros (Todos, Bom, Longe, Não vou, Sem marcação,
 * Novos) e, em cada bairro, os botões Bom | Longe | Não vou (tocar de novo desmarca).
 * Cada toque grava na hora.
 */
class NeighborhoodPicker(
    private val activity: Activity,
    private val repository: NeighborhoodRepository,
    private val onClosed: () -> Unit,
) {
    private enum class Filter { ALL, GOOD, FAR, AVOID, UNMARKED, NEW }

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var filter = Filter.ALL
    private var query = ""
    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout

    fun show(onlyNew: Boolean) {
        try {
            filter = if (onlyNew) Filter.NEW else Filter.ALL
            query = ""
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(build(dialog))
            dialog.setOnDismissListener { onClosed() }
            render()
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Falha ao abrir os bairros", e)
        }
    }

    private fun build(dialog: Dialog): ViewGroup {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(R.color.mc_black))
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val city = repository.city()?.name.orEmpty()
        root.addView(text(activity.getString(R.string.bairros_title, city), 22f, activity.getColor(R.color.mc_yellow), bold = true))
        root.addView(text(activity.getString(R.string.bairros_legend), 13f, activity.getColor(R.color.mc_text_muted)).apply {
            setPadding(0, dp(6), 0, dp(8))
        })
        root.addView(EditText(activity).apply {
            hint = activity.getString(R.string.bairros_search)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(activity.getColor(R.color.mc_text))
            setHintTextColor(activity.getColor(R.color.mc_text_muted))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    render()
                }
            })
        })
        chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(activity).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(Button(activity).apply {
            text = activity.getString(R.string.bairros_add)
            setTextColor(activity.getColor(R.color.mc_yellow))
            setOnClickListener { askNewName() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(activity).apply {
            text = activity.getString(R.string.bairros_close)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        return root
    }

    /** Refaz filtros e lista conforme busca e filtro escolhidos. */
    private fun render() {
        try {
            val all = repository.all()
            chips.removeAllViews()
            listOf(
                Filter.ALL to activity.getString(R.string.bairros_all) + " ${all.size}",
                Filter.GOOD to activity.getString(R.string.bairros_good) + " ${all.count { it.mark == NeighborhoodMark.GOOD }}",
                Filter.FAR to activity.getString(R.string.bairros_far) + " ${all.count { it.mark == NeighborhoodMark.FAR }}",
                Filter.AVOID to activity.getString(R.string.bairros_avoid) + " ${all.count { it.mark == NeighborhoodMark.AVOID }}",
                Filter.UNMARKED to activity.getString(R.string.bairros_unmarked) + " ${all.count { it.mark == null }}",
                Filter.NEW to activity.getString(R.string.bairros_new) + " ${all.count { it.isNew && it.mark == null }}",
            ).forEach { (f, label) ->
                chips.addView(Button(activity).apply {
                    text = label
                    isAllCaps = false
                    textSize = 13f
                    setTextColor(if (f == filter) 0xFF141414.toInt() else activity.getColor(R.color.mc_text))
                    background = pill(if (f == filter) activity.getColor(R.color.mc_yellow) else 0xFF1E1E1E.toInt())
                    setOnClickListener { filter = f; render() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(6) })
            }
            val shown = all.filter { n ->
                (query.isBlank() || DestinationAdvisor.normalize(n.name).contains(DestinationAdvisor.normalize(query))) &&
                    when (filter) {
                        Filter.ALL -> true
                        Filter.GOOD -> n.mark == NeighborhoodMark.GOOD
                        Filter.FAR -> n.mark == NeighborhoodMark.FAR
                        Filter.AVOID -> n.mark == NeighborhoodMark.AVOID
                        Filter.UNMARKED -> n.mark == null
                        Filter.NEW -> n.isNew && n.mark == null
                    }
            }
            list.removeAllViews()
            if (shown.isEmpty()) {
                list.addView(text(activity.getString(R.string.bairros_empty), 14f, activity.getColor(R.color.mc_text_muted)).apply {
                    setPadding(0, dp(16), 0, 0)
                })
            }
            shown.forEach { list.addView(row(it)) }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Falha ao listar bairros", e)
        }
    }

    private fun row(n: Neighborhood): LinearLayout {
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(0xFF1A1A1A.toInt(), radius = 12)
            setPadding(dp(12), dp(6), dp(6), dp(6))
        }
        line.addView(text(n.name + if (n.isNew && n.mark == null) " 🆕" else "", 15f, activity.getColor(R.color.mc_text), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf(
            Triple(NeighborhoodMark.GOOD, R.string.bairros_good, 0xFF2E7D32.toInt()),
            Triple(NeighborhoodMark.FAR, R.string.bairros_far, 0xFFB98A12.toInt()),
            Triple(NeighborhoodMark.AVOID, R.string.bairros_avoid, 0xFFB3261E.toInt()),
        ).forEach { (mark, label, color) ->
            val on = n.mark == mark
            line.addView(Button(activity).apply {
                text = activity.getString(label)
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF9A9A9A.toInt())
                background = pill(if (on) color else 0xFF111111.toInt(), radius = 10)
                contentDescription = "${n.name}: ${activity.getString(label)}"
                // Tocar no que já está marcado desmarca (volta a neutro).
                setOnClickListener {
                    repository.setMark(n.name, if (on) null else mark)
                    render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(4) })
        }
        // Remover (pedido de 19/11): apaga na hora, sem perguntar.
        line.addView(Button(activity).apply {
            text = "✕"
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setTextColor(0xFFFF9C93.toInt())
            background = null
            contentDescription = activity.getString(R.string.bairros_remove, n.name)
            setOnClickListener {
                repository.remove(n.name)
                render()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) })
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(line)
        }
    }

    private fun askNewName() {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.bairros_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.bairros_add)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                repository.add(input.text.toString())
                render()
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun pill(color: Int, radius: Int = 999) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private companion object {
        const val TAG = "MC/Bairros"
    }
}
