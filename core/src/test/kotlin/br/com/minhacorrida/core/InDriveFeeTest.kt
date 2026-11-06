package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.history.InMemoryRideHistoryRepository
import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.InDriveTap
import br.com.minhacorrida.core.usecase.InDriveTapUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Taxa do inDrive (10%): o valor do card é o que o passageiro paga e a taxa sai depois;
 * na Uber o valor do card já é o ganho do motorista (conferido nos ganhos de 05/11).
 */
class InDriveFeeTest {
    private val cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25"))   // R$ 0,85/km
    private val rules = RideRules(BigDecimal("2.00"), operatingCost = cost, inDriveFeePercent = BigDecimal("10"))
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())

    // Pedido de 05/11: R$ 15 em 3,1 + 5,5 km.
    private fun offer(platform: Platform, fare: String = "15") =
        RideOffer(platform, BigDecimal(fare), tripKm = BigDecimal("5.5"), pickupKm = BigDecimal("3.1"))

    @Test
    fun `inDrive desconta 10 por cento antes do R$ por km e do lucro`() {
        val a = evaluate(offer(Platform.INDRIVE), rules)
        assertEquals("13.50", a.metrics.netFare.toPlainString())
        assertEquals("1.50", a.metrics.platformFee.toPlainString())
        assertEquals("1.57", a.metrics.valuePerKm.toPlainString())   // 13,50 ÷ 8,6 km
        assertEquals("6.19", a.profit!!.netProfit.toPlainString())   // 13,50 − 7,31 de gasto
    }

    @Test
    fun `minimo do inDrive e o valor que o passageiro precisa pagar ja com a taxa`() {
        val a = evaluate(offer(Platform.INDRIVE), rules)
        assertEquals("19.12", a.minimum.minimumFare.toPlainString())  // 17,20 ÷ 0,90
        assertEquals("9.89", a.profitAtMinimumFare!!.toPlainString()) // 17,20 − 7,31
    }

    @Test
    fun `Uber nao tem taxa descontada depois`() {
        val a = evaluate(offer(Platform.UBER), rules)
        assertEquals("15.00", a.metrics.netFare.toPlainString())
        assertEquals("1.74", a.metrics.valuePerKm.toPlainString())
        assertEquals("17.20", a.minimum.minimumFare.toPlainString())
    }

    @Test
    fun `card do inDrive mostra o liquido`() {
        val card = RideAnalysisFormatter().card(evaluate(offer(Platform.INDRIVE), rules))
        assertEquals("inDrive — R$ 15,00", card.platform)
        assertEquals("1,57", card.valuePerKm)
        assertEquals("Líquido R$ 13,50\nGasto R$ 7,31", card.profitDetails)
        assertEquals("19,12", card.minimumValue)
    }

    @Test
    fun `contraproposta guardada no historico com a taxa`() {
        val settings = Fixtures.FakeSettings(min = BigDecimal("2.00"), cost = cost, inDriveFee = BigDecimal("10"))
        val repo = InMemoryRideHistoryRepository()
        val tap = InDriveTapUseCase(evaluate, settings, RecordRideUseCase(repo, settings, NoOpLogger), repo, NoOpLogger)
        val pedido = evaluate(offer(Platform.INDRIVE), rules)

        val r = assertIs<InDriveTap.NewFare>(tap.onTap("R$ 20", pedido, null))
        assertEquals(RideRating.GOOD, r.analysis.rating)              // 18,00 ÷ 8,6 = R$ 2,09/km
        val saved = r.saved!!
        assertEquals("18.00", saved.netFare.toPlainString())
        assertEquals("10.69", saved.netProfit.toPlainString())       // 18,00 − 7,31
        assertEquals("2.30", saved.withFare(BigDecimal("22"), BigDecimal("2.00")).valuePerKm.toPlainString())
    }
}
