package br.com.minhacorrida.core.format

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Digitação de valores com a vírgula automática (como em app de banco): o motorista digita só
 * os números e a vírgula fica no lugar certo. Com 2 casas: "6" → "0,06", "60" → "0,60",
 * "600" → "6,00"; com 1 casa (km): "36" → "3,6". Apagar volta o número para a direita.
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

    /** Valor gravado → texto do campo com as casas certas (ex.: 0.6 com 2 casas → "0,60"). */
    fun of(value: BigDecimal, decimals: Int): String =
        value.setScale(decimals.coerceAtLeast(0), RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
