package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.parser.InDriveOfferParser
import br.com.minhacorrida.core.parser.ParserRegistry
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.AnalyzeScreenUseCase
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class MinimumFareTest {
    private val calculate = CalculateRideMetricsUseCase()

    @Test
    fun `minimo de R$ 2,00 por km em 5,2 km exige R$ 10,40`() {
        val metrics = calculate(RideOffer(Platform.INDRIVE, BigDecimal("11"), BigDecimal("1.8"), BigDecimal("3.4")))
        assertEquals("10.40", calculate.minimumFare(metrics, BigDecimal("2.00")).minimumFare.toPlainString())
    }

    @Test
    fun `arredonda para cima no centavo para nunca ficar abaixo do minimo`() {
        // 1,85 × 8,90 = 16,465 -> R$ 16,47
        val metrics = calculate(RideOffer(Platform.UBER, BigDecimal("8.91"), BigDecimal("2.3"), BigDecimal("6.6")))
        assertEquals("16.47", calculate.minimumFare(metrics, BigDecimal("1.85")).minimumFare.toPlainString())
    }

    @Test
    fun `linha do card com o valor minimo`() {
        val useCase = AnalyzeScreenUseCase(
            ParserRegistry(listOf(InDriveOfferParser())),
            calculate,
            Fixtures.FakeSettings(min = BigDecimal("2.00")),
            NoOpLogger,
        )
        val analysis = useCase.analyze(
            "sinet.startup.inDriver",
            Fixtures.INDRIVE_DETAIL_ACCESSIBILITY + Fixtures.INDRIVE_MAP_OCR,
        )!!
        assertEquals("Mín. p/ R$ 2,00/km: R$ 10,40", RideAnalysisFormatter().minimumFare(analysis))
    }
}
