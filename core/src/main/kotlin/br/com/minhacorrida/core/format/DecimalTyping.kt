package br.com.minhacorrida.core.format

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Digitação de valores com a vírgula automática (como em app de banco): o motorista digita só
 * os números e a vírgula fica no lugar certo. Com 2 casas: "6" → "0,06", "60" → "0,60",
 * "600" → "6,00"; com 1 casa (km): "36" → "3,6". Apagar volta o número para a direita.
 * Quem preferir pode digitar a vírgula: "25" + "," + "5" → "25,5" (modo manual, [next]).
 */
object DecimalTyping {
    private const val MAX_DIGITS = 9

    /** Texto digitado (pode ter vírgula, ponto ou lixo) → texto formatado; "" se não houver número. */
    fun format(typed: String, decimals: Int): String {
        val digits = typed.filter(Char::isDigit).trimStart('0').take(MAX_DIGITS)
        if (digits.isEmpty()) return ""
        if (decimals <= 0) return digits
        val padded = digits.padStart(decimals + 1, '0')
        return padded.dropLast(decimals) + "," + padded.takeLast(decimals)
    }

    /**
     * Resultado de uma mudança no campo.
     * @property manual true quando o motorista digitou a vírgula/ponto: os números depois dela
     * viram os centavos, sem a vírgula andar. Volta ao automático quando o campo fica vazio.
     */
    data class Typed(val text: String, val manual: Boolean)

    /**
     * Próximo texto do campo depois de uma digitação.
     * @param old texto que estava no campo (já formatado).
     * @param new texto depois da tecla (pode ter vírgula, ponto ou lixo).
     * @param manual se o campo já estava no modo manual (vírgula digitada).
     */
    fun next(old: String, new: String, decimals: Int, manual: Boolean): Typed {
        if (decimals <= 0) return Typed(format(new, 0), false)
        if (new.none(Char::isDigit) && new.none(::isSeparator)) return Typed("", false)
        val separators = new.count(::isSeparator)
        return when {
            // Acabou de digitar a vírgula: o que está antes dela é o valor inteiro.
            separators > old.count(::isSeparator) -> manual(new, insertedSeparator(old, new), decimals)
            manual && separators == 0 -> {
                val digits = integer(new)
                if (digits == "0" && new.none(Char::isDigit)) Typed("", false) else Typed(digits, true)
            }
            manual -> manual(new, new.indexOfFirst(::isSeparator), decimals)
            else -> Typed(format(new, decimals), false)
        }
    }

    private fun isSeparator(c: Char) = c == ',' || c == '.'

    /** Posição da vírgula/ponto que acabou de entrar (a primeira diferença entre os textos). */
    private fun insertedSeparator(old: String, new: String): Int {
        val diff = new.indices.firstOrNull { it >= old.length || old[it] != new[it] } ?: new.lastIndex
        return if (isSeparator(new[diff])) diff else new.indexOfLast(::isSeparator)
    }

    private fun integer(text: String) = text.filter(Char::isDigit).trimStart('0').take(MAX_DIGITS).ifEmpty { "0" }

    private fun manual(text: String, separatorAt: Int, decimals: Int): Typed {
        val whole = integer(text.substring(0, separatorAt))
        val fraction = text.substring(separatorAt + 1).filter(Char::isDigit).take(decimals)
        return Typed("$whole,$fraction", true)
    }

    /** Valor gravado → texto do campo com as casas certas (ex.: 0.6 com 2 casas → "0,60"). */
    fun of(value: BigDecimal, decimals: Int): String =
        value.setScale(decimals.coerceAtLeast(0), RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
