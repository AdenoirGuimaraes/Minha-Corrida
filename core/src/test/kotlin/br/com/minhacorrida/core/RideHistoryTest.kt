package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideHistoryFormatter
import br.com.minhacorrida.core.history.HistoryPeriod
import br.com.minhacorrida.core.history.InMemoryRideHistoryRepository
import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.history.RideHistoryUseCase
import br.com.minhacorrida.core.history.rangeOf
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Histórico: guarda só corridas verdes, soma as realizadas, filtra por período e apaga após 60 dias. */
class RideHistoryTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 11, 5)
    private var now = today.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
    private val repo = InMemoryRideHistoryRepository()
    // Versa: gasolina R$ 6,30 a 10,5 km/l e R$ 0,25/km de manutenção.
    private val settings = Fixtures.FakeSettings(
        min = BigDecimal("2.00"),
        cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25")),
    )
    private val record = RecordRideUseCase(repo, settings, NoOpLogger) { now }
    private val history = RideHistoryUseCase(repo, settings, NoOpLogger, zone, today = { today })
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())

    private fun analysis(
        fare: String, pickup: String, trip: String, min: String = "2.00", platform: Platform = Platform.UBER,
    ) = evaluate(
        RideOffer(platform, BigDecimal(fare), tripKm = BigDecimal(trip), pickupKm = BigDecimal(pickup)),
        RideRules(BigDecimal(min), operatingCost = settings.cost),
    )

    @Test
    fun `guarda so corrida verde e nao duplica a mesma`() {
        assertNotNull(record(analysis("10.00", "2.2", "1.9")))    // R$ 2,44/km: verde
        assertNull(record(analysis("10.00", "2.2", "1.9")))       // mesma corrida de novo
        assertNull(record(analysis("7.37", "2.8", "3.5")))        // R$ 1,17/km: vermelha
        assertEquals(1, history.page(HistoryPeriod.DAY, today).rides.size)
    }

    @Test
    fun `gasto separado em combustivel e manutencao`() {
        record(analysis("10.00", "2.2", "1.9"))
        val r = history.page(HistoryPeriod.DAY, today).rides.single()
        // 4,1 km × 6,30 ÷ 10,5 = R$ 2,46; 4,1 × 0,25 = R$ 1,03 (arredondado)
        assertEquals("2.46", r.fuelCost!!.toPlainString())
        assertEquals("1.03", r.maintenanceCost!!.toPlainString())
        assertEquals("6.51", r.netProfit.toPlainString())
    }

    @Test
    fun `resumo soma so as corridas marcadas como realizadas`() {
        record(analysis("10.00", "2.2", "1.9"))
        now += 60_000
        record(analysis("20.00", "1.0", "7.0"))
        var page = history.page(HistoryPeriod.DAY, today)
        assertEquals(0, page.summary.doneCount)

        history.setDone(page.rides.first { it.fare.compareTo(BigDecimal("20")) == 0 }.id, true)
        page = history.page(HistoryPeriod.DAY, today)
        val s = page.summary
        assertEquals(1, s.doneCount)
        assertEquals("8.00", s.totalKm.toPlainString())
        // 8 km × 0,60 = 4,80 de combustível; 8 × 0,25 = 2,00 de manutenção
        assertEquals("4.80", s.fuelCost.toPlainString())
        assertEquals("2.00", s.maintenanceCost.toPlainString())
        assertEquals("6.80", s.totalCost.toPlainString())
        assertEquals("13.20", s.netProfit.toPlainString())
    }

    @Test
    fun `semana vai de segunda a domingo e mes do dia 1 ao ultimo`() {
        val week = HistoryPeriod.WEEK.rangeOf(today) // quinta 05/11/2026
        assertEquals(LocalDate.of(2026, 11, 2), week.start)
        assertEquals(LocalDate.of(2026, 11, 8), week.endInclusive)
        val month = HistoryPeriod.MONTH.rangeOf(today)
        assertEquals(LocalDate.of(2026, 11, 1), month.start)
        assertEquals(LocalDate.of(2026, 11, 30), month.endInclusive)
    }

    @Test
    fun `corrida de outro dia aparece na semana mas nao no dia`() {
        now = today.minusDays(2).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        record(analysis("10.00", "2.2", "1.9"))
        assertEquals(0, history.page(HistoryPeriod.DAY, today).rides.size)
        assertEquals(1, history.page(HistoryPeriod.WEEK, today).rides.size)
    }

    @Test
    fun `apaga corridas com mais de 60 dias`() {
        now = today.minusDays(61).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        record(analysis("10.00", "2.2", "1.9"))
        now = today.minusDays(59).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        record(analysis("12.00", "2.2", "1.9"))
        assertEquals(1, history.purgeOld())
    }

    @Test
    fun `textos da aba Corridas`() {
        record(analysis("10.00", "2.2", "1.9"))
        val page = history.page(HistoryPeriod.DAY, today)
        history.setDone(page.rides.single().id, true)
        val done = history.page(HistoryPeriod.DAY, today)
        val f = RideHistoryFormatter(zone)
        assertEquals("Hoje · 05/11", f.periodLabel(done, today))
        assertEquals("1 corrida realizada · 4,1 km", f.countAndKm(done))
        assertEquals("R$ 6,51", f.profit(done))
        assertEquals("R$ 3,49", f.cost(done))
        assertEquals("🟢 15:00 · Uber · R$ 10,00 · 4,1 km · lucro R$ 6,51", f.row(done.rides.single(), HistoryPeriod.DAY))
        assertEquals("02/11 a 08/11", f.periodLabel(history.page(HistoryPeriod.WEEK, today), today))
        assertEquals("Novembro de 2026", f.periodLabel(history.page(HistoryPeriod.MONTH, today), today))
    }

    @Test
    fun `correcao do valor a mao recalcula o lucro e guarda o valor original`() {
        record(analysis("10.00", "2.2", "1.9"))
        val id = history.page(HistoryPeriod.DAY, today).rides.single().id
        val fixed = history.changeFare(id, BigDecimal("20"))!!
        assertEquals("16.51", fixed.netProfit.toPlainString())      // 20,00 − 3,49 de gasto
        assertEquals("🟢 15:00 · Uber · R$ 20,00 (era R$ 10,00) · 4,1 km · lucro R$ 16,51",
            RideHistoryFormatter(zone).row(fixed, HistoryPeriod.DAY))
        assertEquals(null, history.changeFare(id, BigDecimal.ZERO))
    }

    @Test
    fun `corridas selecionadas apaga so as nao marcadas do periodo na tela`() {
        record(analysis("10.00", "2.2", "1.9"))
        now += 60_000
        record(analysis("20.00", "1.0", "7.0"))
        now = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        record(analysis("15.00", "1.0", "5.0"))                       // ontem: fora do período
        val hoje = history.page(HistoryPeriod.DAY, today)
        history.setDone(hoje.rides.first().id, true)

        assertEquals(1, history.keepOnlyDone(HistoryPeriod.DAY, today))
        val depois = history.page(HistoryPeriod.DAY, today)
        assertEquals(1, depois.rides.size)
        assertTrue(depois.rides.single().done)
        assertEquals(1, history.page(HistoryPeriod.DAY, today.minusDays(1)).rides.size)
    }

    @Test
    fun `corrida manual particular entra realizada e com os gastos`() {
        val r = history.registerManual(BigDecimal("25"), BigDecimal("2"), BigDecimal("8"), now)!!
        assertEquals(Platform.PARTICULAR, r.platform)
        assertTrue(r.done)
        assertTrue(r.good)                                             // 25 ÷ 10 km = R$ 2,50/km
        assertEquals("6.00", r.fuelCost!!.toPlainString())             // 10 km × 6,30 ÷ 10,5
        assertEquals("2.50", r.maintenanceCost!!.toPlainString())      // 10 km × 0,25
        assertEquals("16.50", r.netProfit.toPlainString())
        val page = history.page(HistoryPeriod.DAY, today)
        assertEquals(1, page.summary.doneCount)
        assertEquals("🟢 15:00 · Particular · R$ 25,00 · 10 km · lucro R$ 16,50",
            RideHistoryFormatter(zone).row(r, HistoryPeriod.DAY))
    }

    @Test
    fun `corrida manual sem km ate o passageiro e com valores invalidos`() {
        assertEquals("8.00", history.registerManual(BigDecimal("20"), null, BigDecimal("8"), now)!!.totalKm.toPlainString())
        assertNull(history.registerManual(BigDecimal.ZERO, null, BigDecimal("8"), now))
        assertNull(history.registerManual(BigDecimal("20"), null, BigDecimal.ZERO, now))
    }
}
