package br.com.minhacorrida.core

import br.com.minhacorrida.core.km.CommuteCost
import br.com.minhacorrida.core.km.FuelStats
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.km.KmCorrection
import br.com.minhacorrida.core.km.TripStartEstimator
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Teste de 11/11: ~1 km perdido no começo de cada saída até o GPS começar a contar. */
class TripStartAndCorrectionTest {
    private fun p(lat: Double) = GeoPoint(lat, -50.33, 8f, 0)

    @Test
    fun `comeco do trajeto estimado do estacionamento ao 1o ponto do GPS`() {
        // Estacionado em casa; 1º ponto do GPS 0,9 km depois (0,0081° ≈ 900 m).
        val meters = TripStartEstimator.estimate(parking = p(-27.8000), firstFix = p(-27.8081))
        assertTrue(meters in 1_150.0..1_190.0, "~900 m × 1,3 = ~1,17 km, veio $meters")
        assertEquals(0.0, TripStartEstimator.estimate(parking = null, firstFix = p(-27.8)))
        // Mais de 3 km: outro carro ou dado errado → não estima.
        assertEquals(0.0, TripStartEstimator.estimate(parking = p(-27.80), firstFix = p(-27.84)))
    }

    private fun stats(odometer: String) =
        FuelStats(BigDecimal("10.5"), BigDecimal("6.30"), BigDecimal(odometer), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 8))

    @Test
    fun `ajuste pelo painel - 412 km no painel e 398 no GPS da mais 3,5 por cento`() {
        val factor = KmCorrection.factor(stats("412"), BigDecimal("398"))!!
        assertEquals("1.035", factor.toPlainString())
        assertEquals("+3,5%", KmCorrection.percentLabel(factor))

        val commute = CommuteCost.of(BigDecimal("50.0"), BigDecimal("17.7"), BigDecimal("6.30"), BigDecimal("10.5"), factor)!!
        assertEquals("51.8", commute.totalKm.toPlainString())          // 50 × 1,035
        assertEquals("34.1", commute.commuteKm.toPlainString())
    }

    @Test
    fun `sem GPS suficiente nao ajusta e o fator fica entre 0,8 e 1,3`() {
        assertNull(KmCorrection.factor(stats("412"), BigDecimal("150")))      // GPS cobriu menos da metade
        assertNull(KmCorrection.factor(stats("412"), BigDecimal.ZERO))
        assertEquals("1.30", KmCorrection.factor(stats("412"), BigDecimal("210"))!!.toPlainString())
        assertEquals("−5,0%", KmCorrection.percentLabel(BigDecimal("0.950")))
    }
}
