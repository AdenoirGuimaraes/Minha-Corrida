package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.parser.InDriveOfferParser
import br.com.minhacorrida.core.parser.ParserRegistry
import br.com.minhacorrida.core.parser.UberOfferParser
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.AnalyzeScreenUseCase
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyzeScreenUseCaseTest {
    private val settings = Fixtures.FakeSettings(BigDecimal("1.50"))
    private val useCase = AnalyzeScreenUseCase(
        ParserRegistry(listOf(UberOfferParser(), InDriveOfferParser())),
        CalculateRideMetricsUseCase(), settings, NoOpLogger,
    )
    private val formatter = RideAnalysisFormatter()

    @Test
    fun `fluxo completo na tela da Uber`() {
        val a = useCase.analyze("com.ubercab.driver", Fixtures.UBER_OFFER)!!
        assertEquals(RideRating.BELOW_MINIMUM, a.rating)
        assertEquals("R$ 1,00/km", formatter.headline(a))
        assertEquals("8,9 km (6,6 + 2,3) • R$ 8,91", formatter.details(a))
    }

    @Test
    fun `classifica como boa quando atinge o minimo`() {
        settings.setMinValuePerKm(BigDecimal("0.90"))
        val a = useCase.analyze("sinet.startup.inDriver", Fixtures.INDRIVE_DETAIL)!!
        assertEquals(RideRating.GOOD, a.rating)
        assertEquals("R$ 0,94/km", formatter.headline(a))
    }

    @Test
    fun `app nao suportado retorna null`() =
        assertNull(useCase.analyze("com.whatsapp", Fixtures.UBER_OFFER))
}

class AnalyzeScreenInDriveMapTest {
    private val useCase = br.com.minhacorrida.core.usecase.AnalyzeScreenUseCase(
        br.com.minhacorrida.core.parser.ParserRegistry(
            listOf(br.com.minhacorrida.core.parser.InDriveOfferParser()),
        ),
        br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase(),
        Fixtures.FakeSettings(),
        br.com.minhacorrida.core.port.NoOpLogger,
    )

    @Test
    fun `print de 30-10 - R$ 11 em 3,4 + 1,8 km da R$ 2,12 por km (e nao 6,11)`() {
        val pkg = "sinet.startup.inDriver"
        kotlin.test.assertTrue(useCase.needsVisualReading(pkg, Fixtures.INDRIVE_DETAIL_ACCESSIBILITY))
        val a = useCase.analyze(pkg, Fixtures.INDRIVE_DETAIL_ACCESSIBILITY + Fixtures.INDRIVE_MAP_OCR)!!
        assertEquals("5.20", a.metrics.totalKm.toPlainString())
        assertEquals("2.12", a.metrics.valuePerKm.toPlainString())
    }
}
