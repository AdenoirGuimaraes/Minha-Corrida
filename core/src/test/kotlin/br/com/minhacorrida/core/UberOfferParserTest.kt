package br.com.minhacorrida.core

import br.com.minhacorrida.core.parser.UberOfferParser
import br.com.minhacorrida.core.parser.VisualReadingMode
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
