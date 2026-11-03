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

    // Prints de 03/11: inDrive na frente; o OCR da metade de baixo pega um pedaço da
    // lista do inDrive ACIMA do cartão da Uber.
    private val print1 = listOf(
        "R$ 10", "Preço justo", "Diego", "5.0(24)", "Mercearia Lk (Rua João Lemos",
        "UberX", "R$ 7,80", "* 4,95 (176)", "9 min (2.2 km)", "Rua Frei Gabriel, Universitário, Lages",
        "6 minutos (1.9 km)", "Rua Juvelina Ribeiro Antunes, 146,", "Caravágio, Lages", "Aceitar",
    )
    private val print2 = listOf(
        "R$ 11", "Preço justo", "Evelin", "5.0(61)", "Rua Prudente de Morais 887 (Conta",
        "UberX", "R$ 16,99", "* 5,00 (52)", "12 min (74 km)", "Santa Monica, Lages",
        "16 minutos (8.6 km)", "Rua Dom Joaquim do Arco Verde, 195,", "Santa Rita, Lages", "Aceitar",
    )

    @Test
    fun `valor e nota da Uber nao sao confundidos com a lista do inDrive acima (print 1 de 03-11)`() {
        val offer = parser.parse(print1)!!
        assertEquals(0, BigDecimal("7.80").compareTo(offer.fare))
        assertEquals(0, BigDecimal("4.95").compareTo(offer.passengerRating!!))
        assertEquals(0, BigDecimal("2.2").compareTo(offer.pickupKm!!))
        assertEquals(0, BigDecimal("1.9").compareTo(offer.tripKm))
    }

    @Test
    fun `distancia sem ponto e corrigida pelos minutos (print 2 de 03-11, 74 km em 12 min)`() {
        val offer = parser.parse(print2)!!
        assertEquals(0, BigDecimal("16.99").compareTo(offer.fare))
        assertEquals(0, BigDecimal("7.4").compareTo(offer.pickupKm!!))
        assertEquals(0, BigDecimal("8.6").compareTo(offer.tripKm))
    }

    @Test
    fun `distancia longa possivel nao e alterada`() {
        val offer = parser.parse(listOf("R$ 45,00", "25 min (30 km)", "40 minutos (42 km)"))!!
        assertEquals(0, BigDecimal("30").compareTo(offer.pickupKm!!))
        assertEquals(0, BigDecimal("42").compareTo(offer.tripKm))
    }
}
