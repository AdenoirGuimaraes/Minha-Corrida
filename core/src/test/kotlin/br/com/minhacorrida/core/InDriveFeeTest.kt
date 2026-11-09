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
    fun `minimo verde do inDrive com lucro minimo inclui a taxa`() {
        val a = evaluate(offer(Platform.INDRIVE), rules.copy(minNetProfit = BigDecimal("12.00")))
        // (gasto 7,31 + lucro 12,00) ÷ 0,90 = 21,46 > 19,12 do R$/km.
        assertEquals("21.46", a.minimum.greenFare.toPlainString())
        val atGreen = evaluate(offer(Platform.INDRIVE, "21.46"), rules.copy(minNetProfit = BigDecimal("12.00")))
        assertEquals(RideRating.GOOD, atGreen.rating)
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
}
