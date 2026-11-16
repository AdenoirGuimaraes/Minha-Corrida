package br.com.minhacorrida.core

import br.com.minhacorrida.core.parser.InDriveOfferParser
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InDriveOfferParserTest {
    private val parser = InDriveOfferParser()

    private fun assertKm(expected: String, actual: BigDecimal?) =
        assertEquals(0, BigDecimal(expected).compareTo(actual!!), "esperado $expected km, veio $actual")

    @Test
    fun `lista principal e ignorada e nao pede OCR`() {
        assertNull(parser.parse(Fixtures.INDRIVE_LIST))
        assertFalse(parser.needsVisualReading(Fixtures.INDRIVE_LIST))
    }

    @Test
    fun `oferta aberta so com acessibilidade pede leitura do mapa`() {
        assertNull(parser.parse(Fixtures.INDRIVE_DETAIL_ACCESSIBILITY))
        assertTrue(parser.needsVisualReading(Fixtures.INDRIVE_DETAIL_ACCESSIBILITY))
    }

    @Test
    fun `com OCR do mapa soma as duas bolhas (bug do print de 30-10)`() {
        val texts = Fixtures.INDRIVE_DETAIL_ACCESSIBILITY + Fixtures.INDRIVE_MAP_OCR
        val offer = parser.parse(texts)!!
        assertEquals(0, BigDecimal("11").compareTo(offer.fare))
        // "~1,8 km" do cartão = até a passageira; a viagem é a outra bolha (15/11).
        assertKm("1.8", offer.pickupKm)
        assertKm("3.4", offer.tripKm)
        assertFalse(parser.needsVisualReading(texts))
    }

    @Test
    fun `busca e identificada pelo til mesmo com bolhas em outra ordem`() {
        // Géssica: Guarujá → Havan (Guarujá), viagem curta; os 7,3 km eram até ela.
        val offer = parser.parse(Fixtures.INDRIVE_DETAIL)!!
        assertKm("7.3", offer.pickupKm)
        assertKm("3.3", offer.tripKm)
    }

    @Test
    fun `pedido de 15-11 - bolha azul ate o ponto A e busca, bolha verde ate o B e viagem`() {
        // Print das 21:51 (Tchuco, R$ 10): azul "6 min 2,6 km" (até A), verde "4 min 1,4 km" (A → B), cartão "~2,6 km".
        val offer = parser.parse(
            listOf("Pedido de viagem", "4 min 1,4 km", "6 min 2,6 km", "R$ 2,4/km ~2,6 km", "R$ 10", "Aceitar por R$ 10"),
        )!!
        assertKm("2.6", offer.pickupKm)
        assertEquals(6, offer.pickupMinutes)
        assertKm("1.4", offer.tripKm)
        assertEquals(4, offer.tripMinutes)
    }

    @Test
    fun `bolha lida em dobro nao e somada duas vezes`() {
        val offer = parser.parse(listOf("7,3 km", "7,3 km", "3,3 km", "~7,3 km", "Aceitar por R$ 10"))!!
        assertKm("3.3", offer.tripKm)
    }

    @Test
    fun `distancias iguais nas duas bolhas`() {
        val offer = parser.parse(listOf("5 km", "8 min", "5 km", "~5 km", "Aceitar por R$ 12"))!!
        assertKm("5", offer.pickupKm)
        assertKm("5", offer.tripKm)
    }
}
