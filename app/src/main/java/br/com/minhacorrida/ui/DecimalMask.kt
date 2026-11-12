package br.com.minhacorrida.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import br.com.minhacorrida.core.format.DecimalTyping
import java.math.BigDecimal

/**
 * Vírgula automática nos campos de número: o motorista digita só os números (teclado numérico)
 * e o campo mostra "0,60", "6,40", "10,4"… A formatação está em [DecimalTyping] (testada no núcleo).
 */
class DecimalMask private constructor(private val field: EditText, private val decimals: Int) : TextWatcher {

    private var formatting = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
        if (formatting || s == null) return
        try {
            val formatted = DecimalTyping.format(s.toString(), decimals)
            if (formatted == s.toString()) return
            formatting = true
            s.replace(0, s.length, formatted)
            field.setSelection(field.text.length)
        } catch (e: Exception) {
            android.util.Log.w("MC/DecimalMask", "Falha ao formatar o campo", e)
        } finally {
            formatting = false
        }
    }

    companion object {
        private val masks = java.util.WeakHashMap<EditText, Int>()

        /** Liga a vírgula automática com [decimals] casas (2 para R$, 1 para km e %). */
        fun attach(field: EditText, decimals: Int): EditText {
            if (masks.containsKey(field)) return field
            masks[field] = decimals
            field.inputType = InputType.TYPE_CLASS_NUMBER
            field.addTextChangedListener(DecimalMask(field, decimals))
            // Texto que já estava no campo (ex.: valor padrão) passa a ter as casas certas.
            if (field.text.isNotEmpty()) field.setText(field.text.toString())
            return field
        }

        /** Mostra um valor gravado com as casas certas (sempre use isto em campo com máscara). */
        fun set(field: EditText, value: BigDecimal?) {
            val decimals = masks[field] ?: 2
            field.setText(value?.let { DecimalTyping.of(it, decimals) }.orEmpty())
        }
    }
}
