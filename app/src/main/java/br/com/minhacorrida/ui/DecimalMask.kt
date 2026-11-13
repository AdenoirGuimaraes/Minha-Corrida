package br.com.minhacorrida.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.widget.EditText
import br.com.minhacorrida.core.format.DecimalTyping
import java.math.BigDecimal

/**
 * Vírgula nos campos de número: o motorista digita só os números e a vírgula fica no lugar
 * ("2550" → "25,50"), ou digita a vírgula ele mesmo ("25,5"). As regras estão em
 * [DecimalTyping] (testadas no núcleo); aqui só ligamos ao EditText.
 * O cursor fica sempre no fim: digitar entra pela direita e apagar sai pela direita,
 * como em app de banco (com o cursor no meio a vírgula automática se perderia).
 */
class DecimalMask private constructor(private val field: EditText, private val decimals: Int) : TextWatcher {

    private var formatting = false
    private var last = ""
    private var manual = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
        if (formatting || s == null) return
        try {
            val next = DecimalTyping.next(last, s.toString(), decimals, manual)
            manual = next.manual
            last = next.text
            if (next.text != s.toString()) {
                formatting = true
                s.replace(0, s.length, next.text)
            }
            field.setSelection(field.text.length)
        } catch (e: Exception) {
            android.util.Log.w("MC/DecimalMask", "Falha ao formatar o campo", e)
        } finally {
            formatting = false
        }
    }

    /** Texto vindo do app (valor gravado): entra já formatado, no modo automático. */
    private fun show(text: String) {
        formatting = true
        try {
            last = text
            manual = false
            field.setText(text)
            field.setSelection(field.text.length)
        } finally {
            formatting = false
        }
    }

    private fun cursorToEnd() = field.post { field.setSelection(field.text.length) }

    companion object {
        private val masks = java.util.WeakHashMap<EditText, DecimalMask>()

        /** Liga a vírgula com [decimals] casas (2 para R$, 1 para km e %; 0 = inteiro). */
        fun attach(field: EditText, decimals: Int): EditText {
            if (masks.containsKey(field)) return field
            val mask = DecimalMask(field, decimals)
            masks[field] = mask
            // Teclado numérico com a tecla de vírgula/ponto (aceita as duas).
            if (decimals > 0) {
                field.keyListener = DigitsKeyListener.getInstance("0123456789,.")
                field.setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            } else {
                field.inputType = InputType.TYPE_CLASS_NUMBER
            }
            field.setSelectAllOnFocus(false)
            field.isLongClickable = false
            field.setOnClickListener { mask.cursorToEnd() }
            field.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) mask.cursorToEnd() }
            field.addTextChangedListener(mask)
            // Texto que já estava no campo (ex.: valor padrão) passa a ter as casas certas.
            if (field.text.isNotEmpty()) mask.show(DecimalTyping.format(field.text.toString(), decimals))
            return field
        }

        /** Mostra um valor gravado com as casas certas (sempre use isto em campo com máscara). */
        fun set(field: EditText, value: BigDecimal?) {
            val mask = masks[field]
            val text = value?.let { DecimalTyping.of(it, mask?.decimals ?: 2) }.orEmpty()
            if (mask == null) field.setText(text) else mask.show(text)
        }
    }
}
