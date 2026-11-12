package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideHistoryFormatter
import br.com.minhacorrida.core.goal.DailyGoalCalculator
import br.com.minhacorrida.core.history.DateRange
import br.com.minhacorrida.core.history.HistoryPeriod
import br.com.minhacorrida.core.history.InMemoryRideHistoryRepository
import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.history.RideHistoryPage
import br.com.minhacorrida.core.history.RideHistorySummary
import br.com.minhacorrida.core.km.CommuteChoiceRepository
import br.com.minhacorrida.core.km.DailyCommuteUseCase
import br.com.minhacorrida.core.km.FuelEntry
import br.com.minhacorrida.core.km.FuelLogRepository
import br.com.minhacorrida.core.km.KmLogRepository
import br.com.minhacorrida.core.km.KmSettingsRepository
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedidos de 12/11: check do deslocamento por dia e minutos guardados no histórico. */
class CommuteCheckAndMinutesTest {
    private val day = LocalDate.of(2026, 11, 12)
    private val settings = Fixtures.FakeSettings(
        min = BigDecimal("2.00"),
        cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25")),
    )
    private val kmSettings = object : KmSettingsRepository {
        override fun isKmTrackingEnabled() = true
        override fun setKmTrackingEnabled(enabled: Boolean) = Unit
    }
    private val kmLog = object : KmLogRepository {
        override fun addMeters(date: LocalDate, meters: Double) = Unit
        override fun kmBetween(start: LocalDate, endExclusive: LocalDate) =
            if (day >= start && day < endExclusive) BigDecimal("52.3") else BigDecimal.ZERO
    }
    private val noFuel = object : FuelLogRepository {
        override fun all() = emptyList<FuelEntry>()
        override fun save(entry: FuelEntry) = Unit
        override fun delete(id: String) = Unit
    }
    private val marked = mutableSetOf<LocalDate>()
    private val choices = object : CommuteChoiceRepository {
        override fun isIncluded(date: LocalDate) = date in marked
        override fun setIncluded(date: LocalDate, included: Boolean) { if (included) marked += date else marked -= date }
    }
    private val commute = DailyCommuteUseCase(settings, kmSettings, kmLog, noFuel, choices, NoOpLogger)

    @Test
    fun `sem o check o deslocamento so aparece, com o check entra nas contas`() {
        val rides = mapOf(day to BigDecimal("17.7"))
        val off = commute.forPeriod(rides) { _, _ -> BigDecimal.ZERO }
        assertNull(off.included)
        assertEquals("20.76", off.excluded!!.fuelCost.toPlainString())   // 34,6 km × 6,30 ÷ 10,5

        choices.setIncluded(day, true)
        val on = commute.forPeriod(rides) { _, _ -> BigDecimal.ZERO }
        assertEquals("20.76", on.included!!.fuelCost.toPlainString())
        assertNull(on.excluded)
        assertTrue(on.days.getValue(day).second)
    }

    @Test
    fun `dia sem corrida realizada nao tem deslocamento nem check`() {
        assertTrue(commute.forPeriod(emptyMap()) { _, _ -> BigDecimal.ZERO }.days.isEmpty())
    }

    @Test
    fun `linha do deslocamento desmarcado aparece como nao descontada e fica fora do total`() {
        val excluded = commute.forPeriod(mapOf(day to BigDecimal("17.7"))) { _, _ -> BigDecimal.ZERO }.excluded!!
        val page = RideHistoryPage(
            HistoryPeriod.DAY, DateRange(day, day.plusDays(1)), emptyList(),
            RideHistorySummary(5, BigDecimal("17.7"), BigDecimal("59.70"), BigDecimal("10.87"), BigDecimal("5.31"), BigDecimal("43.52"), 0),
        )
        val progress = DailyGoalCalculator.progress(BigDecimal("43.52"), emptyList(), null)
        val f = RideHistoryFormatter()
        assertEquals("R$ 16,18", f.setAside(page, progress, null))       // sem o deslocamento
        assertEquals("R$ 43,52", f.kept(progress))
        assertTrue(
            f.setAsideDetails(page, progress, null, excluded)
                .contains("🚗 Gasolina do deslocamento 34,6 km (rodou 52,3 km) R$ 20,76 (não descontada)"),
        )
    }

    @Test
    fun `corrida gravada guarda os minutos e o card do historico mostra o lucro por minuto`() {
        val repo = InMemoryRideHistoryRepository()
        val analysis = EvaluateRideUseCase(CalculateRideMetricsUseCase())(
            RideOffer(Platform.UBER, BigDecimal("12.00"), tripKm = BigDecimal("2.3"), pickupKm = BigDecimal("1.8"), pickupMinutes = 5, tripMinutes = 6),
            RideRules(BigDecimal("2.00"), operatingCost = settings.cost),
        )
        val saved = RecordRideUseCase(repo, settings, NoOpLogger)(analysis, acceptedByDriver = true)!!
        assertEquals(11, saved.minutes)
        assertFalse(saved.minutesEstimated)
        assertEquals("0.77", saved.profitPerMinute!!.toPlainString())   // 8,51 ÷ 11
        val card = RideHistoryFormatter().card(saved, HistoryPeriod.DAY, BigDecimal("2.00"), null)
        assertTrue(card.profitDetails.startsWith("R$ 0,77/min · 11 min\n"), card.profitDetails)
    }
}
