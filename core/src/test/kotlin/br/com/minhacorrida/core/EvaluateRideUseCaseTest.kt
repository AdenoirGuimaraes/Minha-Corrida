package br.com.minhacorrida.core

import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Regra usada pelo simulador do app e pelo leitor de tela. */
class EvaluateRideUseCaseTest {
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())
    private val offer = RideOffer(
        Platform.UBER, BigDecimal("7.04"), tripKm = BigDecimal("3.0"), pickupKm = BigDecimal("4.0"),
        passengerRating = BigDecimal("4.82"),
    )

    @Test
    fun `simulacao muda de verde para vermelho conforme o minimo`() {
        assertEquals(RideRating.GOOD, evaluate(offer, RideRules(BigDecimal("1.00"))).rating)
        assertEquals(RideRating.BELOW_MINIMUM, evaluate(offer, RideRules(BigDecimal("2.00"))).rating)
    }

    @Test
    fun `simulacao com custos calcula o lucro e sem custos nao`() {
        val cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10"))
        assertEquals("2.63", evaluate(offer, RideRules(BigDecimal("1.00"), operatingCost = cost)).profit!!.netProfit.toPlainString())
        assertNull(evaluate(offer, RideRules(BigDecimal("1.00"))).profit)
    }

    @Test
    fun `simulacao com nota minima acima da do passageiro fica vermelha`() {
        val a = evaluate(offer, RideRules(BigDecimal("1.00"), minPassengerRating = BigDecimal("4.90")))
        assertEquals(RideRating.BELOW_MINIMUM, a.rating)
    }
}
