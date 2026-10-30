package br.com.minhacorrida.core

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalculateRideMetricsUseCaseTest {
    private val calculate = CalculateRideMetricsUseCase()

    @Test
    fun `uber - R$ 8,91 em 6,6 + 2,3 km da R$ 1,00 por km`() {
        val m = calculate(RideOffer(Platform.UBER, BigDecimal("8.91"), BigDecimal("2.3"), BigDecimal("6.6")))
        assertEquals("8.90", m.totalKm.toPlainString())
        assertEquals("1.00", m.valuePerKm.toPlainString())
        assertTrue(m.includesPickup)
    }

    @Test
    fun `indrive - R$ 10 em 3,3 + 7,3 km da R$ 0,94 por km`() {
        val m = calculate(RideOffer(Platform.INDRIVE, BigDecimal("10"), BigDecimal("7.3"), BigDecimal("3.3")))
        assertEquals("10.60", m.totalKm.toPlainString())
        assertEquals("0.94", m.valuePerKm.toPlainString())
    }

    @Test
    fun `sem busca usa apenas o trajeto`() {
        val m = calculate(RideOffer(Platform.INDRIVE, BigDecimal("10"), BigDecimal("7.0"), null))
        assertEquals("1.43", m.valuePerKm.toPlainString())
        assertFalse(m.includesPickup)
    }

    @Test
    fun `oferta com distancia zero e rejeitada`() {
        assertFailsWith<IllegalArgumentException> {
            RideOffer(Platform.UBER, BigDecimal("10"), BigDecimal.ZERO, null)
        }
    }
}
