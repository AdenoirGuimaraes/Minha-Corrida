package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.model.RideTime
import br.com.minhacorrida.core.parser.UberOfferParser
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedido de 11/11: lucro por minuto (busca + viagem); abaixo do mínimo, card amarelo. */
class ProfitPerMinuteTest {
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())
    private val cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25")) // R$ 0,85/km
    private val rules = RideRules(BigDecimal("1.50"), operatingCost = cost, minProfitPerMinute = BigDecimal("0.60"))

    // R$ 12,00 em 1,8 + 2,3 km: R$ 2,93/km; gasto 4,1 × 0,85 = R$ 3,49; lucro R$ 8,51.
    private fun offer(pickupMin: Int?, tripMin: Int?) = RideOffer(
        Platform.UBER, BigDecimal("12.00"), tripKm = BigDecimal("2.3"), pickupKm = BigDecimal("1.8"),
        pickupMinutes = pickupMin, tripMinutes = tripMin,
    )

    @Test
    fun `corrida rapida fica verde e corrida presa no transito fica amarela`() {
        val rapida = evaluate(offer(5, 6), rules)                       // 8,51 ÷ 11 = 0,77/min
        assertEquals("0.77", rapida.profitPerMinute!!.toPlainString())
        assertEquals(RideRating.GOOD, rapida.rating)

        val lenta = evaluate(offer(10, 15), rules)                      // 8,51 ÷ 25 = 0,34/min
        assertEquals("0.34", lenta.profitPerMinute!!.toPlainString())
        assertEquals(RideRating.LOW_PROFIT, lenta.rating)
    }

    @Test
    fun `minimo verde inclui o lucro por minuto`() {
        val lenta = evaluate(offer(10, 15), rules)
        // gasto 3,49 + 0,60 × 25 min = R$ 18,49
        assertEquals("18.49", lenta.minimum.greenFare.toPlainString())
        assertEquals(RideRating.GOOD, evaluate(offer(10, 15).copy(fare = BigDecimal("18.49")), rules).rating)
        val card = RideAnalysisFormatter().card(lenta)
        assertEquals("R$ 1,50/km\nR$ 0,60/min", card.minimumLabel)
        assertTrue(card.profitDetails.startsWith("R$ 0,34/min · 25 min\n"))
    }

    @Test
    fun `sem minutos na tela estima pelos km e sem custos nao classifica pelo minuto`() {
        val time = RideTime.of(offer(null, 6))                          // 1,8 km × 2,4 = 4,32 → 5 min
        assertEquals(11, time.minutes)
        assertTrue(time.estimated)
        assertFalse(RideTime.of(offer(5, 6)).estimated)

        val semCusto = evaluate(offer(10, 15), rules.copy(operatingCost = null))
        assertNull(semCusto.profitPerMinute)
        assertEquals(RideRating.GOOD, semCusto.rating)
    }

    @Test
    fun `minutos lidos do cartao da Uber`() {
        val o = UberOfferParser().parse(listOf("UberX", "R$ 8,56", "★ 5,00 (28)", "5 min (1.8 km)", "6 minutos (2.3 km)", "Tenho interesse"))!!
        assertEquals(5, o.pickupMinutes)
        assertEquals(6, o.tripMinutes)
    }
}
