package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.DecimalTyping
import br.com.minhacorrida.core.parser.NumberParser
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pedido de 12/11: campos de valor com a vírgula automática enquanto digita. */
class DecimalTypingTest {
    @Test
    fun `digitar 0 6 0 vira 0,60 e a virgula anda conforme os numeros`() {
        assertEquals("0,06", DecimalTyping.format("6", 2))
        assertEquals("0,60", DecimalTyping.format("0,060", 2))   // campo "0,06" + digitou 0
        assertEquals("6,00", DecimalTyping.format("0,600", 2))
        assertEquals("125,50", DecimalTyping.format("12550", 2))
        assertEquals("3,6", DecimalTyping.format("36", 1))
        assertEquals("10,0", DecimalTyping.format("100", 1))
    }

    @Test
    fun `apagar volta o numero e campo vazio fica vazio`() {
        assertEquals("0,06", DecimalTyping.format("0,6", 2))      // apagou o último 0 de "0,60"
        assertEquals("", DecimalTyping.format("0,0", 2))
        assertEquals("", DecimalTyping.format("", 2))
        assertEquals("45230", DecimalTyping.format("45230", 0))
    }

    @Test
    fun `valor gravado aparece com as casas certas e volta igual`() {
        assertEquals("0,60", DecimalTyping.of(BigDecimal("0.6"), 2))
        assertEquals("2,00", DecimalTyping.of(BigDecimal("2"), 2))
        assertEquals("10,4", DecimalTyping.of(BigDecimal("10.4"), 1))
        // O texto formatado continua sendo lido certo pelo app (vírgula ou ponto).
        assertEquals(0, BigDecimal("0.60").compareTo(NumberParser.parse(DecimalTyping.of(BigDecimal("0.6"), 2))))
        // E reformatar o próprio texto não muda nada.
        assertEquals("2,00", DecimalTyping.format(DecimalTyping.of(BigDecimal("2"), 2), 2))
    }

    /** Simula o motorista digitando tecla por tecla, como o TextWatcher do app faz. */
    private fun type(keys: String, decimals: Int = 2): String {
        var text = ""
        var manual = false
        for (key in keys) {
            val typed = if (key == '<') text.dropLast(1) else text + key
            val next = DecimalTyping.next(text, typed, decimals, manual)
            text = next.text
            manual = next.manual
        }
        return text
    }

    @Test
    fun `pedido de 12-11 - corrigir valor aceita a virgula digitada e a automatica`() {
        assertEquals("25,50", type("2550"))       // automática
        assertEquals("25,5", type("25,5"))        // vírgula digitada
        assertEquals("25,50", type("25,505"))     // no máximo 2 casas depois da vírgula
        assertEquals("25,", type("25,"))
        assertEquals("0,5", type(",5"))
        assertEquals("8,5", type("8.5", 1))       // ponto também vale
        assertEquals("25", type("25,<"))          // apagou a vírgula: fica 25
        assertEquals("", type("25,<<<"))          // apagou tudo: volta ao automático
        assertEquals("0,03", type("25,<<<3"))
        assertEquals(0, BigDecimal("25").compareTo(NumberParser.parse(type("25,"))))
    }
}
