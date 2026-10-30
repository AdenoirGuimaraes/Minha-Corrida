package br.com.minhacorrida.core.parser

import java.math.BigDecimal
import java.math.RoundingMode

/** Padrões de texto compartilhados pelos parsers das plataformas. */
internal object TextPatterns {

    private const val NUMBER = """(\d+(?:[.,]\d+)*)"""
    private const val UNIT = """(km|m)(?![\p{L}/])"""

    /** Distância simples: "3,3 km", "800 m". Não casa "12 min" nem "R$ 1/km". */
    val DISTANCE = Regex("""$NUMBER\s*$UNIT""", RegexOption.IGNORE_CASE)

    /** Distância entre parênteses (Uber): "12 min (6.6 km)". */
    val PAREN_DISTANCE = Regex("""\(\s*$NUMBER\s*(km|m)\s*\)""", RegexOption.IGNORE_CASE)

    /** Distância aproximada (inDrive): "~7,0 km". */
    val APPROX_DISTANCE = Regex("""~\s*$NUMBER\s*$UNIT""", RegexOption.IGNORE_CASE)

    /** Valor em reais: "R$ 10", "R$ 8,91", "R$ 1.234,56". */
    val MONEY = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?|\d+(?:[.,]\d{1,2})?)""")

    /** Converte número + unidade para km (escala de 3 casas). */
    fun toKm(number: String, unit: String): BigDecimal? {
        val value = NumberParser.parse(number) ?: return null
        val km = if (unit.equals("m", ignoreCase = true)) {
            value.divide(BigDecimal(1000), 3, RoundingMode.HALF_UP)
        } else {
            value.setScale(3, RoundingMode.HALF_UP)
        }
        return km.takeIf { it.signum() > 0 }
    }

    /** True quando a distância vem precedida de "~" no mesmo texto. */
    fun isApproximate(text: String, match: MatchResult): Boolean =
        text.substring(0, match.range.first).trimEnd().endsWith("~")

    /**
     * Primeiro valor em R$ que represente o preço da corrida.
     * Ignora preço por km ("R$ 1/km") e acréscimos ("+R$ 2,00").
     */
    fun findFirstFare(texts: List<String>): BigDecimal? {
        for (text in texts) {
            for (match in MONEY.findAll(text)) {
                val after = text.substring(match.range.last + 1).trimStart()
                val before = text.substring(0, match.range.first).trimEnd()
                if (after.startsWith("/") || before.endsWith("+")) continue
                NumberParser.parse(match.groupValues[1])
                    ?.takeIf { it.signum() > 0 }
                    ?.let { return it }
            }
        }
        return null
    }
}
