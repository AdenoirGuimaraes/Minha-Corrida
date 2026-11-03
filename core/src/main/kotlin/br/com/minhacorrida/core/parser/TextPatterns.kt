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

    /**
     * Nota do passageiro: "4,82 (22)", "* 4,82 (22) Verificado" (Uber, OCR) ou "4.92" sozinho (inDrive).
     * Exige o número no início do texto (até 3 símbolos antes, ex. "★ ") e, depois dele,
     * "(n)" ou fim do texto — assim não confunde com distância ("4,9 km") nem com R$/km.
     */
    private val PASSENGER_RATING = Regex("""^\W{0,3}([1-5][.,]\d{1,2})(?=\s*\(\d+\)|\s*$)""")

    /** @return a primeira nota de passageiro encontrada (1,00 a 5,00), ou null. */
    fun findPassengerRating(texts: List<String>): BigDecimal? =
        texts.firstNotNullOfOrNull { text ->
            PASSENGER_RATING.find(text.trim())?.groupValues?.get(1)
                ?.let(NumberParser::parse)
                ?.takeIf { it >= BigDecimal.ONE && it <= FIVE }
        }

    private val FIVE = BigDecimal(5)

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
    fun findFirstFare(texts: List<String>): BigDecimal? =
        findFirstFareText(texts)?.let(NumberParser::parse)

    /**
     * Mesmo critério de [findFirstFare], mas devolve o número como está na tela
     * (ex.: "7,04" ou "704"), para o parser decidir se o OCR perdeu a vírgula.
     */
    fun findFirstFareText(texts: List<String>): String? = fareTexts(texts).firstOrNull()?.value

    /**
     * Todos os valores de corrida da tela, com a posição (índice do texto) de cada um,
     * na ordem de leitura. Ignora R$/km e acréscimos ("+R$ 2,00").
     */
    fun fareTexts(texts: List<String>): List<IndexedValue<String>> {
        val result = mutableListOf<IndexedValue<String>>()
        texts.forEachIndexed { index, text ->
            for (match in MONEY.findAll(text)) {
                val after = text.substring(match.range.last + 1).trimStart()
                val before = text.substring(0, match.range.first).trimEnd()
                if (after.startsWith("/") || before.endsWith("+")) continue
                val raw = match.groupValues[1]
                if ((NumberParser.parse(raw)?.signum() ?: 0) > 0) result += IndexedValue(index, raw)
            }
        }
        return result
    }
}
