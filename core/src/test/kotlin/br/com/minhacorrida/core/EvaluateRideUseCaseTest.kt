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

    // Lucro líquido mínimo: R$ 7,04 em 7 km com custo R$ 0,63/km = lucro R$ 2,63.
    private val cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10"))

    @Test
    fun `verde com R$ por km e lucro acima dos minimos`() =
        assertEquals(RideRating.GOOD, evaluate(offer, RideRules(BigDecimal("1.00"), operatingCost = cost, minNetProfit = BigDecimal("2.00"))).rating)

    @Test
    fun `amarelo com R$ por km ok mas lucro abaixo do minimo`() =
        assertEquals(RideRating.LOW_PROFIT, evaluate(offer, RideRules(BigDecimal("1.00"), operatingCost = cost, minNetProfit = BigDecimal("3.00"))).rating)

    @Test
    fun `vermelho continua vermelho mesmo com lucro minimo`() =
        assertEquals(RideRating.BELOW_MINIMUM, evaluate(offer, RideRules(BigDecimal("2.00"), operatingCost = cost, minNetProfit = BigDecimal("1.00"))).rating)

    @Test
    fun `nota ruim fica vermelha antes do amarelo`() =
        assertEquals(RideRating.BELOW_MINIMUM, evaluate(offer, RideRules(BigDecimal("1.00"), BigDecimal("4.90"), cost, minNetProfit = BigDecimal("3.00"))).rating)

    @Test
    fun `sem custos informados o lucro minimo nao se aplica`() =
        assertEquals(RideRating.GOOD, evaluate(offer, RideRules(BigDecimal("1.00"), minNetProfit = BigDecimal("50"))).rating)

    // 3º quadrado "Mín. verde": o maior entre o valor do R$/km mínimo e o do lucro mínimo.
    @Test
    fun `minimo verde usa o lucro minimo quando ele exige mais`() {
        val m = evaluate(offer, RideRules(BigDecimal("1.00"), operatingCost = cost, minNetProfit = BigDecimal("3.00"))).minimum
        assertEquals("7.00", m.minimumFare.toPlainString())
        assertEquals("7.41", m.greenFare.toPlainString()) // gasto 4,41 + lucro 3,00
        val card = br.com.minhacorrida.core.format.RideAnalysisFormatter()
            .card(evaluate(offer, RideRules(BigDecimal("1.00"), operatingCost = cost, minNetProfit = BigDecimal("3.00"))))
        assertEquals("7,41", card.minimumValue)
        assertEquals("R$ 1,00/km\nLucro R$ 3,00", card.minimumLabel)
    }

    @Test
    fun `minimo verde usa o R$ por km quando ele exige mais`() {
        val m = evaluate(offer, RideRules(BigDecimal("1.00"), operatingCost = cost, minNetProfit = BigDecimal("1.00"))).minimum
        assertEquals("7.00", m.greenFare.toPlainString()) // 4,41 + 1,00 = 5,41 < 7,00
    }

    @Test
    fun `corrida no valor do minimo verde fica verde`() {
        val rules = RideRules(BigDecimal("1.00"), operatingCost = cost, minNetProfit = BigDecimal("3.00"))
        val green = evaluate(offer, rules).minimum.greenFare
        assertEquals(RideRating.GOOD, evaluate(offer.copy(fare = green), rules).rating)
        assertEquals(RideRating.LOW_PROFIT, evaluate(offer.copy(fare = green - BigDecimal("0.01")), rules).rating)
    }

    @Test
    fun `sem custos o minimo verde e so o do R$ por km`() {
        val m = evaluate(offer, RideRules(BigDecimal("1.00"), minNetProfit = BigDecimal("3.00"))).minimum
        assertNull(m.minNetProfit)
        assertEquals("7.00", m.greenFare.toPlainString())
    }
}
