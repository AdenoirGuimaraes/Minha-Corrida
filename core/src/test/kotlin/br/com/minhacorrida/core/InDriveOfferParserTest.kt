package br.com.minhacorrida.core

import br.com.minhacorrida.core.parser.InDriveOfferParser
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InDriveOfferParserTest {
    private val parser = InDriveOfferParser()

    @Test
    fun `lista - le valor e trajeto, sem busca`() {
        val offer = parser.parse(Fixtures.INDRIVE_LIST)!!
        assertEquals(0, BigDecimal("10").compareTo(offer.fare))
        assertEquals(0, BigDecimal("7.0").compareTo(offer.tripKm))
        assertNull(offer.pickupKm)
    }

    @Test
    fun `detalhe - le busca da bolha do mapa e ignora rotulo do trajeto`() {
        val offer = parser.parse(Fixtures.INDRIVE_DETAIL)!!
        assertEquals(0, BigDecimal("10").compareTo(offer.fare))
        assertEquals(0, BigDecimal("7.3").compareTo(offer.tripKm))
        assertEquals(0, BigDecimal("3.3").compareTo(offer.pickupKm!!))
    }

    @Test
    fun `nao confunde R$ por km com o valor da corrida`() {
        val offer = parser.parse(listOf("R$ 1/km", "~5 km", "R$ 15"))!!
        assertEquals(0, BigDecimal("15").compareTo(offer.fare))
    }

    @Test
    fun `sem trajeto retorna null`() = assertNull(parser.parse(listOf("R$ 10", "Desempenho")))
}
