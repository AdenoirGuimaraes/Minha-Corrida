package br.com.minhacorrida.core

import br.com.minhacorrida.core.parser.UberOfferParser
import br.com.minhacorrida.core.parser.VisualReadingMode
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UberOfferParserTest {
    private val parser = UberOfferParser()

    @Test
    fun `le valor, busca e viagem da tela da Uber`() {
        val offer = parser.parse(Fixtures.UBER_OFFER)!!
        assertEquals(0, BigDecimal("8.91").compareTo(offer.fare))
        assertEquals(0, BigDecimal("6.6").compareTo(offer.pickupKm!!))
        assertEquals(0, BigDecimal("2.3").compareTo(offer.tripKm))
    }

    @Test
    fun `aceita distancia em metros`() {
        val offer = parser.parse(listOf("R$ 7,00", "3 min (800 m)", "10 min (4,2 km)"))!!
        assertEquals(0, BigDecimal("0.8").compareTo(offer.pickupKm!!))
    }

    @Test
    fun `ignora acrescimo dinamico`() {
        val offer = parser.parse(listOf("+R$ 2,00", "R$ 12,50", "(1 km)", "(5 km)"))!!
        assertEquals(0, BigDecimal("12.50").compareTo(offer.fare))
    }

    @Test
    fun `tela sem oferta retorna null`() = assertNull(parser.parse(listOf("Você está online", "R$ 120,00 hoje")))

    @Test
    fun `tela sem texto pela acessibilidade pede OCR continuo`() =
        assertEquals(VisualReadingMode.LOWER_SCREEN_CONTINUOUS, parser.visualReadingMode(emptyList()))

    @Test
    fun `OCR do cartao da Uber (print de 30-10)`() {
        val ocr = listOf(
            "UberX", "R$ 8,91", "* 5,00 (12)", "12 min (6.6 km)", "R. Ábramo Baú, Do Tributo, Lages",
            "5 minutos (2.3 km)", "Rua 31 de Março, 953, Guarujá, Lages", "Aceitar",
        )
        val offer = parser.parse(ocr)!!
        assertEquals(0, BigDecimal("6.6").compareTo(offer.pickupKm!!))
        assertEquals(0, BigDecimal("2.3").compareTo(offer.tripKm))
    }

    @Test
    fun `OCR que perde os parenteses ainda le as duas distancias`() {
        val offer = parser.parse(listOf("R$ 8,91", "12 min 6.6 km", "5 minutos 2.3 km"))!!
        assertEquals(0, BigDecimal("6.6").compareTo(offer.pickupKm!!))
        assertEquals(0, BigDecimal("2.3").compareTo(offer.tripKm))
    }

    @Test
    fun `ganhos do dia na tela inicial nao dispensam o OCR (diagnostico de 30-10)`() {
        val homeScreen = listOf("Página inicial", "R$ 13,92", "Hoje", "Você está online", "1-6 min")
        assertNull(parser.parse(homeScreen))
        assertEquals(VisualReadingMode.LOWER_SCREEN_CONTINUOUS, parser.visualReadingMode(homeScreen))
    }

    @Test
    fun `cartao lido so pela parte de cima nao vira oferta e e marcado como parcial`() {
        val topOnly = listOf("UberX", "R$ 8,91", "* 5,00 (12)", "12 min (6.6 km)", "R. Ábramo Baú, Do Tributo, Lages")
        assertNull(parser.parse(topOnly))
        assertTrue(parser.isPartialOffer(topOnly))
    }

    @Test
    fun `cartao completo nao e parcial e tela sem valor tambem nao`() {
        assertFalse(parser.isPartialOffer(Fixtures.UBER_OFFER))
        assertFalse(parser.isPartialOffer(listOf("Você está online", "1-6 min")))
    }

    @Test
    fun `OCR sem virgula le os centavos (log de 02-11, R$ 704 virou R$ 100,57 por km)`() {
        val ocr = listOf("UberX", "R$ 704", "* 4,82 (22)", "10 min (4.0 km)", "6 minutos (3.0 km)", "Aceitar")
        assertEquals(0, BigDecimal("7.04").compareTo(parser.parse(ocr)!!.fare))
    }

    @Test
    fun `valor alto sem virgula tambem vira centavos e valor curto fica igual`() {
        assertEquals(0, BigDecimal("104.50").compareTo(parser.parse(listOf("R$ 10450", "(4 km)", "(20 km)"))!!.fare))
        assertEquals(0, BigDecimal("15").compareTo(parser.parse(listOf("R$ 15", "(1 km)", "(5 km)"))!!.fare))
    }

    @Test
    fun `com OCR primeiro, o valor da oferta vence os ganhos do dia`() {
        val ocr = listOf("UberX", "R$ 8,91", "12 min (6.6 km)", "5 minutos (2.3 km)", "Aceitar")
        val offer = parser.parse(ocr + listOf("Página inicial", "R$ 13,92", "Hoje"))!!
        assertEquals(0, BigDecimal("8.91").compareTo(offer.fare))
    }
}
