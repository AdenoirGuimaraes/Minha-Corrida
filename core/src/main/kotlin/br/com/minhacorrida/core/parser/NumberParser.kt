package br.com.minhacorrida.core.parser

import java.math.BigDecimal

/**
 * Converte números exibidos pelos apps em BigDecimal, aceitando os dois formatos
 * vistos nas telas: pt-BR ("8,91", "1.234,56") e en-US ("6.6", "1,234.56").
 */
object NumberParser {

    /**
     * @param raw texto numérico, ex.: "7,3", "6.6", "1.234,56".
     * @return o valor, ou null se o texto não for um número válido.
     */
    fun parse(raw: String): BigDecimal? {
        val s = raw.trim().replace(" ", "").replace(" ", "")
        if (s.isEmpty() || s.none(Char::isDigit)) return null

        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')

        // O separador que aparece por último é o decimal; o outro é de milhar.
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lastComma >= 0 -> s.replace(',', '.')
            lastDot >= 0 && s.length - lastDot - 1 == 3 -> s.replace(".", "") // "1.234" = milhar
            else -> s
        }
        return normalized.toBigDecimalOrNull()
    }
}
