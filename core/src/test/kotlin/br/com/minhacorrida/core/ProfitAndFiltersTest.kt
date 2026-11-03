package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.overlay.OverlayVisibilityPolicy
import br.com.minhacorrida.core.parser.InDriveOfferParser
import br.com.minhacorrida.core.parser.ParserRegistry
import br.com.minhacorrida.core.parser.UberOfferParser
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.AnalyzeScreenUseCase
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lucro real líquido, nota mínima do passageiro, apps ligados e frase da voz. */
class ProfitAndFiltersTest {
    private val settings = Fixtures.FakeSettings(min = BigDecimal("1.00"))
    private val useCase = AnalyzeScreenUseCase(
        ParserRegistry(listOf(UberOfferParser(), InDriveOfferParser())),
        CalculateRideMetricsUseCase(), settings, NoOpLogger,
    )
    private val formatter = RideAnalysisFormatter()

    // Card da Uber do log de 02/11: R$ 7,04 em 4,0 + 3,0 km, nota 4,82.
    private val uberCard = listOf(
        "UberX", "R$ 7,04", "* 4,82 (22)", "10 min (4.0 km)", "6 minutos (3.0 km)", "Aceitar",
    )

    @Test
    fun `custo por km = combustivel dividido pelo consumo + outros custos`() {
        val cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10"), BigDecimal("0.15"))
        assertEquals(0, BigDecimal("0.78").compareTo(cost.costPerKm))
    }

    @Test
    fun `lucro real liquido desconta o combustivel do km total`() {
        // 7 km × R$ 0,63/km = R$ 4,41 de gasto → lucro R$ 2,63.
        settings.cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10"))
        val a = useCase.analyze("com.ubercab.driver", uberCard)!!
        assertEquals("4.41", a.profit!!.cost.toPlainString())
        assertEquals("2.63", a.profit!!.netProfit.toPlainString())
        assertEquals("Lucro: R$ 2,63 (gasto R$ 4,41)", formatter.profit(a))
    }

    @Test
    fun `corrida que nao paga o combustivel mostra prejuizo`() {
        settings.cost = OperatingCost(BigDecimal("6.30"), BigDecimal("5"))
        val a = useCase.analyze("com.ubercab.driver", uberCard)!!
        assertEquals("Prejuízo: R$ 1,78 (gasto R$ 8,82)", formatter.profit(a))
    }

    @Test
    fun `sem custos informados o card nao mostra lucro`() {
        val a = useCase.analyze("com.ubercab.driver", uberCard)!!
        assertNull(a.profit)
        assertNull(formatter.profit(a))
    }

    @Test
    fun `nota do passageiro e lida na Uber e no inDrive`() {
        assertEquals(0, BigDecimal("4.82").compareTo(useCase.analyze("com.ubercab.driver", uberCard)!!.offer.passengerRating!!))
        val inDrive = useCase.analyze(
            "sinet.startup.inDriver",
            Fixtures.INDRIVE_DETAIL_ACCESSIBILITY + Fixtures.INDRIVE_MAP_OCR,
        )!!
        assertEquals(0, BigDecimal("5.0").compareTo(inDrive.offer.passengerRating!!))
    }

    @Test
    fun `nota abaixo do minimo deixa a corrida vermelha mesmo com bom R$ por km`() {
        settings.minRating = BigDecimal("4.90")
        val a = useCase.analyze("com.ubercab.driver", uberCard)!!
        assertEquals(RideRating.BELOW_MINIMUM, a.rating)
        assertTrue(a.lowPassengerRating)
        assertEquals("Nota 4,82 — abaixo do mínimo", formatter.passengerRating(a))
    }

    @Test
    fun `passageiro sem nota nao e recusado`() {
        settings.minRating = BigDecimal("4.90")
        val a = useCase.analyze("com.ubercab.driver", listOf("R$ 7,04", "10 min (4.0 km)", "6 minutos (3.0 km)"))!!
        assertFalse(a.lowPassengerRating)
        assertEquals(RideRating.GOOD, a.rating)
    }

    @Test
    fun `app desligado nao e lido`() {
        settings.platforms = setOf(Platform.INDRIVE)
        assertNull(useCase.analyze("com.ubercab.driver", uberCard))
    }

    @Test
    fun `frase da voz com valor por extenso e o app`() {
        val a = useCase.analyze("com.ubercab.driver", uberCard)!!
        assertEquals("Aceitar corrida. 7 reais e 4 centavos. Uber", formatter.speech(a))
        assertEquals("11 reais", formatter.spokenMoney(BigDecimal("11")))
        assertEquals("1 real e 1 centavo", formatter.spokenMoney(BigDecimal("1.01")))
    }

    @Test
    fun `mesma corrida com e sem nota lida nao reabre o card`() {
        val semNota = RideOffer(Platform.UBER, BigDecimal("7.04"), BigDecimal("3.0"), BigDecimal("4.0"))
        val comNota = semNota.copy(passengerRating = BigDecimal("4.82"))
        assertTrue(comNota.sameRideAs(semNota))
        val policy = OverlayVisibilityPolicy()
        policy.onDismissed(semNota)
        assertFalse(policy.shouldShow(comNota))
    }

    @Test
    fun `resumo de custo mostra a margem do minimo`() {
        val cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10"))
        assertEquals(
            "Seu custo: R$ 0,63/km. Com o mínimo de R$ 2,00/km, sobram R$ 1,37 de lucro por km.",
            formatter.costSummary(cost, BigDecimal("2.00")),
        )
        assertTrue(formatter.costSummary(cost, BigDecimal("0.50")).contains("prejuízo"))
    }

    @Test
    fun `textos do card de 3 quadrados`() {
        settings.cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10"))
        val card = formatter.card(useCase.analyze("com.ubercab.driver", uberCard)!!)
        assertEquals("Uber — R$ 7,04", card.platform)
        assertEquals("★ 4,82", card.rating)
        assertEquals("1,01", card.valuePerKm)
        assertEquals("7 km\n(4 + 3)", card.kmDetails)
        assertEquals("Lucro", card.profitLabel)
        assertEquals("2,63", card.profitValue)
        assertEquals("Corrida R$ 7,04\nGasto R$ 4,41", card.profitDetails)
        assertEquals("7,00", card.minimumValue)
        // Mínimo R$ 7,00 − gasto R$ 4,41 = R$ 2,59 de lucro se a corrida pagasse o mínimo.
        assertEquals("Lucro no mínimo\nR$ 2,59", card.minimumLabel)
    }

    @Test
    fun `card sem custos e com prejuizo`() {
        val semCusto = formatter.card(useCase.analyze("com.ubercab.driver", uberCard)!!)
        assertEquals("—", semCusto.profitValue)
        assertEquals("Corrida R$ 7,04\nInforme os custos", semCusto.profitDetails)
        assertEquals("Mín. p/\nR$ 1,00/km", semCusto.minimumLabel)
        settings.cost = OperatingCost(BigDecimal("6.30"), BigDecimal("5"))
        val prejuizo = formatter.card(useCase.analyze("com.ubercab.driver", uberCard)!!)
        assertEquals("Prejuízo", prejuizo.profitLabel)
        assertEquals("1,78", prejuizo.profitValue)
    }

    @Test
    fun `nota abaixo do minimo aparece na faixa do topo`() {
        settings.minRating = BigDecimal("4.90")
        assertEquals("★ 4,82 abaixo do mín.", formatter.card(useCase.analyze("com.ubercab.driver", uberCard)!!).rating)
    }
}
