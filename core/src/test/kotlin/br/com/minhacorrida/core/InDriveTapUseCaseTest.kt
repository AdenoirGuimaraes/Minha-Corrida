package br.com.minhacorrida.core

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

/** O toque no pedido do inDrive só refaz o card (teste de 05/11: R$ 15 em 3,1 + 5,5 km). */
class InDriveTapUseCaseTest {
    private val settings = Fixtures.FakeSettings(
        min = BigDecimal("2.00"),
        cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25")),
    )
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())
    private val tap = InDriveTapUseCase(evaluate, settings, NoOpLogger)

    // Alessandra: R$ 15 em 3,1 + 5,5 km = R$ 1,74/km (vermelho com mínimo de R$ 2,00).
    private val pedido = evaluate(
        RideOffer(Platform.INDRIVE, BigDecimal("15"), tripKm = BigDecimal("5.5"), pickupKm = BigDecimal("3.1")),
        RideRules(BigDecimal("2.00")),
    )

    @Test
    fun `aceitar pelo valor do pedido refaz o card com ele`() {
        val r = assertIs<InDriveTap.NewFare>(tap.onTap("Aceitar por R$ 15", pedido))
        assertEquals(RideRating.BELOW_MINIMUM, r.analysis.rating)
    }

    @Test
    fun `contraproposta refaz o card com o valor tocado`() {
        val r = assertIs<InDriveTap.NewFare>(tap.onTap("R$ 18", pedido))
        assertEquals(RideRating.GOOD, r.analysis.rating)            // 18 ÷ 8,6 km = R$ 2,09/km
        assertEquals(0, BigDecimal("18").compareTo(r.analysis.offer.fare))
    }

    @Test
    fun `pular e fechar recusam`() {
        assertIs<InDriveTap.Skipped>(tap.onTap("Pular", pedido))
        assertIs<InDriveTap.Skipped>(tap.onTap("Fechar", pedido))
    }

    @Test
    fun `toques que nao sao valor nem recusa sao ignorados`() {
        assertIs<InDriveTap.Ignored>(tap.onTap("Ofereça sua tarifa", pedido))
        assertIs<InDriveTap.Ignored>(tap.onTap("R$ 2,1/km", pedido))
        assertIs<InDriveTap.Ignored>(tap.onTap("R$ 18", null))
    }
}
