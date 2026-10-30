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
