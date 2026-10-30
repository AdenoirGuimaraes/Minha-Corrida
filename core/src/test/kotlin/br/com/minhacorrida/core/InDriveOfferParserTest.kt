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
        assertKm("1.8", offer.tripKm)
        assertKm("3.4", offer.pickupKm)
        assertFalse(parser.needsVisualReading(texts))
    }

    @Test
    fun `trajeto e identificado pelo til mesmo com bolhas em outra ordem`() {
        val offer = parser.parse(Fixtures.INDRIVE_DETAIL)!!
        assertKm("7.3", offer.tripKm)
        assertKm("3.3", offer.pickupKm)
    }

    @Test
    fun `bolha lida em dobro nao e somada duas vezes`() {
        val offer = parser.parse(listOf("7,3 km", "7,3 km", "3,3 km", "~7,3 km", "Aceitar por R$ 10"))!!
        assertKm("3.3", offer.pickupKm)
    }

    @Test
    fun `distancias iguais nas duas bolhas`() {
        val offer = parser.parse(listOf("5 km", "8 min", "5 km", "~5 km", "Aceitar por R$ 12"))!!
        assertKm("5", offer.pickupKm)
        assertKm("5", offer.tripKm)
    }
}
