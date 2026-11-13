package br.com.minhacorrida.core

import br.com.minhacorrida.core.market.OfferLogRepository
import br.com.minhacorrida.core.market.OfferLogUseCase
import br.com.minhacorrida.core.market.OfferMarket
import br.com.minhacorrida.core.market.OfferSample
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedido de 13/11 (item 7): os mínimos estão de acordo com o município? */
class OfferMarketTest {
    private class FakeLog : OfferLogRepository {
        val list = mutableListOf<OfferSample>()
        override fun add(sample: OfferSample) { list += sample }
        override fun since(fromMillis: Long) = list.filter { it.atMillis >= fromMillis }
    }

    private val cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25"))
    private val rules = RideRules(BigDecimal("2.00"), operatingCost = cost, inDriveFeePercent = BigDecimal("10"))
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())

    @Test
    fun `cada oferta conta uma vez mesmo lida varias vezes`() {
        val log = FakeLog()
        val useCase = OfferLogUseCase(log, NoOpLogger)
        val a = evaluate(RideOffer(Platform.UBER, BigDecimal("15"), tripKm = BigDecimal("5.5"), pickupKm = BigDecimal("3.1")), rules)
        assertTrue(useCase.seen(a, 0))
        assertFalse(useCase.seen(a, 1_000))                                  // mesma tela, 1 s depois
        assertFalse(useCase.seen(a, 9 * 60_000))
        assertTrue(useCase.seen(a, 11 * 60_000))                             // voltou 11 min depois: conta de novo
        val b = evaluate(RideOffer(Platform.INDRIVE, BigDecimal("15"), tripKm = BigDecimal("5.5"), pickupKm = BigDecimal("3.1")), rules)
        assertTrue(useCase.seen(b, 11 * 60_000 + 500))
        assertEquals(3, log.list.size)
        assertEquals("1.57", log.list.last().netPerKm.toPlainString())       // líquido do inDrive (13,50 ÷ 8,6)
        assertEquals("6.19", log.list.last().profit!!.toPlainString())
    }

    private fun sample(perKm: String, profit: String?, platform: Platform = Platform.UBER) =
        OfferSample(0, platform, BigDecimal("10.00"), BigDecimal("5.0"), BigDecimal(perKm), profit?.let(::BigDecimal), 12)

    @Test
    fun `mediana, quanto passa no minimo e a leitura do resultado`() {
        // 20 ofertas: R$/km 1,00 … 2,90 (passo 0,10); lucro = R$/km × 3.
        val samples = (0 until 20).map { i ->
            val perKm = BigDecimal("1.00") + BigDecimal("0.10") * BigDecimal(i)
            sample(perKm.toPlainString(), (perKm * BigDecimal(3)).toPlainString(), if (i % 4 == 0) Platform.INDRIVE else Platform.UBER)
        }
        val check = OfferMarket.check(samples, BigDecimal("2.00"), BigDecimal("6.30"))!!
        assertEquals(20, check.offers)
        assertEquals(mapOf(Platform.UBER to 15, Platform.INDRIVE to 5), check.byPlatform)
        assertEquals("2.00", check.medianPerKm.toPlainString())            // posição 9,5 → 10ª (2,00)
        assertEquals("2.40", check.topQuarterPerKm.toPlainString())
        assertEquals(50, check.reachPerKm)                                  // 2,00 … 2,90
        assertEquals(45, check.reachProfit)                                 // lucro ≥ 6,30 → R$/km ≥ 2,10
        assertEquals(45, check.reachBoth)
        assertEquals("👉 Mínimos EQUILIBRADOS com o que o município paga.", OfferMarket.verdict(check))

        val text = OfferMarket.text(check, BigDecimal("2.00"), BigDecimal("6.30"), "últimos 30 dias")
        assertTrue(text.contains("20 oferta(s) lidas (Uber 15 · inDrive 5)"))
        assertTrue(text.contains("Metade das ofertas paga até R$ 2,00/km; 1 em cada 4 paga R$ 2,40/km ou mais."))
        assertTrue(text.contains("Seu mínimo R$ 2,00/km: 50% das ofertas atingem."))
    }

    @Test
    fun `minimos altos, folgados e poucas ofertas`() {
        val low = List(20) { sample("1.50", "3.00") }
        assertTrue(OfferMarket.verdict(OfferMarket.check(low, BigDecimal("2.00"), null)!!).contains("ALTOS"))
        assertTrue(OfferMarket.verdict(OfferMarket.check(low, BigDecimal("1.20"), null)!!).contains("FOLGADOS"))
        assertTrue(OfferMarket.verdict(OfferMarket.check(low.take(5), BigDecimal("1.20"), null)!!).contains("poucas"))
        assertNull(OfferMarket.check(emptyList(), BigDecimal("2.00"), null))
        assertEquals(0, OfferMarket.check(low, BigDecimal("2.00"), BigDecimal("5.00"))!!.reachProfit)   // lucro 3,00 < 5,00
    }
}
