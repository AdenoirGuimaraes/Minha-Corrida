package br.com.minhacorrida.core

import br.com.minhacorrida.core.history.HistoryPeriod
import br.com.minhacorrida.core.history.InMemoryRideHistoryRepository
import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.history.RideHistoryUseCase
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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O app confia no botão tocado no pedido do inDrive (teste de 05/11: R$ 15 em 3,1 + 5,5 km). */
class InDriveTapUseCaseTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 11, 5)
    private val now = today.atTime(19, 15).atZone(zone).toInstant().toEpochMilli()
    private val repo = InMemoryRideHistoryRepository()
    private val settings = Fixtures.FakeSettings(
        min = BigDecimal("2.00"),
        cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25")),
    )
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())
    private val record = RecordRideUseCase(repo, settings, NoOpLogger) { now }
    private val tap = InDriveTapUseCase(evaluate, settings, record, repo, NoOpLogger)
    private val history = RideHistoryUseCase(repo, settings, NoOpLogger, zone, today = { today })

    // Alessandra: R$ 15 em 3,1 + 5,5 km = R$ 1,74/km (vermelho com mínimo de R$ 2,00).
    private val pedido = evaluate(
        RideOffer(Platform.INDRIVE, BigDecimal("15"), tripKm = BigDecimal("5.5"), pickupKm = BigDecimal("3.1")),
        RideRules(BigDecimal("2.00")),
    )

    private fun saved() = history.page(HistoryPeriod.DAY, today).rides

    @Test
    fun `aceitar pedido vermelho pelo valor dele nao guarda`() {
        val r = assertIs<InDriveTap.NewFare>(tap.onTap("Aceitar por R$ 15", pedido, null))
        assertEquals(RideRating.BELOW_MINIMUM, r.analysis.rating)
        assertNull(r.saved)
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `contraproposta que deixa verde refaz o card e guarda`() {
        val r = assertIs<InDriveTap.NewFare>(tap.onTap("R$ 18", pedido, null))
        assertEquals(RideRating.GOOD, r.analysis.rating)            // 18 ÷ 8,6 km = R$ 2,09/km
        assertEquals(0, BigDecimal("18").compareTo(r.analysis.offer.fare))
        val s = assertNotNull(r.saved)
        assertEquals("15.00", s.originalFare!!.toPlainString())
        assertEquals(listOf(s), saved())
    }

    @Test
    fun `contraproposta que continua vermelha refaz o card mas nao guarda`() {
        val r = assertIs<InDriveTap.NewFare>(tap.onTap("R$ 16", pedido, null))
        assertEquals(RideRating.BELOW_MINIMUM, r.analysis.rating)   // 16 ÷ 8,6 km = R$ 1,86/km
        assertNull(r.saved)
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `trocar de contraproposta substitui a corrida guardada`() {
        val first = assertIs<InDriveTap.NewFare>(tap.onTap("R$ 18", pedido, null)).saved!!
        val second = assertIs<InDriveTap.NewFare>(tap.onTap("R$ 19", pedido, first.id)).saved!!
        assertEquals(listOf(second), saved())
    }

    @Test
    fun `pular recusa o pedido e apaga o que ele tinha guardado`() {
        val first = assertIs<InDriveTap.NewFare>(tap.onTap("R$ 18", pedido, null)).saved!!
        val skipped = assertIs<InDriveTap.Skipped>(tap.onTap("Pular", pedido, first.id))
        assertEquals(first.id, skipped.removedId)
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `toques que nao sao valor nem recusa sao ignorados`() {
        assertIs<InDriveTap.Ignored>(tap.onTap("Ofereça sua tarifa", pedido, null))
        assertIs<InDriveTap.Ignored>(tap.onTap("R$ 2,1/km", pedido, null))
        assertIs<InDriveTap.Ignored>(tap.onTap("R$ 18", null, null))
    }
}
