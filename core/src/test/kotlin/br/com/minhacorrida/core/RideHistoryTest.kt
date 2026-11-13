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
        val semDeducao = br.com.minhacorrida.core.goal.DailyGoalCalculator.progress(done.summary.netProfit, emptyList(), null)
        assertEquals("R$ 3,49", f.setAside(done, semDeducao))
        assertEquals("R$ 6,51", f.kept(semDeducao))
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
    fun `toque longo corrige os km e recalcula gastos, R$ por km e cor`() {
        record(analysis("10.00", "2.2", "1.9"))                       // 4,1 km
        val id = history.page(HistoryPeriod.DAY, today).rides.single().id
        val r = history.changeKm(id, BigDecimal("1.0"), BigDecimal("2.0"))!!
        assertEquals("3.00", r.totalKm.toPlainString())
        assertEquals("3.33", r.valuePerKm.toPlainString())
        assertNull(history.changeKm(id, null, BigDecimal.ZERO))      // viagem precisa ter km
        assertEquals("3.00", history.page(HistoryPeriod.DAY, today).rides.single().totalKm.toPlainString())
    }

    @Test
    fun `km das corridas realizadas no intervalo (media do painel)`() {
        record(analysis("10.00", "2.2", "1.9"))                       // 4,1 km, realizada
        now += 60_000
        record(analysis("20.00", "1.0", "7.0"))                       // 8 km, não realizada
        history.setDone(history.page(HistoryPeriod.DAY, today).rides.first { it.totalKm.compareTo(BigDecimal("4.1")) == 0 }.id, true)
        assertEquals(0, BigDecimal("4.1").compareTo(history.doneKmBetween(today, today.plusDays(1))))
        assertEquals(0, BigDecimal.ZERO.compareTo(history.doneKmBetween(today.plusDays(1), today.plusDays(2))))
    }

    @Test
    fun `toque longo exclui so a corrida escolhida`() {
        record(analysis("10.00", "2.2", "1.9"))
        now += 60_000
        record(analysis("20.00", "1.0", "7.0"))
        val rides = history.page(HistoryPeriod.DAY, today).rides
        assertTrue(history.delete(rides.first().id))
        assertEquals(1, history.page(HistoryPeriod.DAY, today).rides.size)
        assertTrue(!history.delete("nao-existe"))
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
    fun `registrar corrida do inDrive desconta a taxa (corrida de 08-11, R$ 14)`() {
        settings.inDriveFee = BigDecimal("10")
        val r = history.registerManual(BigDecimal("14"), BigDecimal("3"), BigDecimal("3.4"), now, Platform.INDRIVE)!!
        assertEquals(Platform.INDRIVE, r.platform)
        assertTrue(r.done)
        assertEquals("12.60", r.netFare.toPlainString())                // 14 − 10%
        assertEquals("1.97", r.valuePerKm.toPlainString())              // 12,60 ÷ 6,4 km
        assertTrue(!r.good)                                             // abaixo de R$ 2,00/km
        assertEquals("12.60", history.page(HistoryPeriod.DAY, today).summary.totalFare.toPlainString())
    }

    @Test
    fun `card da corrida guardada igual ao card da oferta (Jana, R$ 13 no inDrive)`() {
        settings.inDriveFee = BigDecimal("10")
        val r = history.registerManual(BigDecimal("13"), BigDecimal("1.2"), BigDecimal("3.3"), now, Platform.INDRIVE)!!
        val card = RideHistoryFormatter(zone).card(r, HistoryPeriod.DAY, BigDecimal("2.00"), BigDecimal("7.00"))
        assertEquals("inDrive — R$ 13,00", card.platform)
        assertEquals("15:00", card.rating)
        assertEquals("2,60", card.valuePerKm)                           // 11,70 ÷ 4,5 km
        assertEquals("4,5 km\n(1,2 + 3,3)", card.kmDetails)
        assertEquals("7,87", card.profitValue)                          // 11,70 − 3,83
        assertEquals("Líquido R$ 11,70\nGasto R$ 3,83", card.profitDetails)
        assertEquals("13,00", card.minimumValue)                        // (3,83 + 7,00) ÷ 0,9 = 12,04 → para cima (inDrive)
        assertEquals("R$ 2,00/km\nLucro R$ 7,00", card.minimumLabel)
    }

    @Test
    fun `card da corrida mostra o valor original e sem lucro minimo so o R$ por km`() {
        val r = history.registerManual(BigDecimal("12"), null, BigDecimal("4"), now, Platform.UBER)!!
            .withFare(BigDecimal("14"), BigDecimal("2.00"))
        val card = RideHistoryFormatter(zone).card(r, HistoryPeriod.MONTH, BigDecimal("2.00"), null)
        assertEquals("Uber — R$ 14,00 (era 12,00)", card.platform)
        assertEquals("05/11 15:00", card.rating)
        assertEquals("4 km\n(só trajeto)", card.kmDetails)
        assertEquals("8,00", card.minimumValue)                         // 2,00 × 4 km
        assertEquals("R$ 2,00/km", card.minimumLabel)
    }

    @Test
    fun `registrar corrida da Uber nao desconta taxa`() {
        settings.inDriveFee = BigDecimal("10")
        val r = history.registerManual(BigDecimal("12.06"), null, BigDecimal("4"), now, Platform.UBER)!!
        assertEquals("12.06", r.netFare.toPlainString())
        assertEquals("3.02", r.valuePerKm.toPlainString())
    }

    @Test
    fun `corrida aceita pelo motorista e guardada mesmo vermelha`() {
        assertNull(record(analysis("7.37", "2.8", "3.5")))
        val saved = record(analysis("7.37", "2.8", "3.5"), acceptedByDriver = true)!!
        assertTrue(!saved.good)
    }

    @Test
    fun `corrida manual sem km ate o passageiro e com valores invalidos`() {
        assertEquals("8.00", history.registerManual(BigDecimal("20"), null, BigDecimal("8"), now)!!.totalKm.toPlainString())
        assertNull(history.registerManual(BigDecimal.ZERO, null, BigDecimal("8"), now))
        assertNull(history.registerManual(BigDecimal("20"), null, BigDecimal.ZERO, now))
    }

    @Test
    fun `corrida amarela tambem e guardada e aparece com bolinha amarela`() {
        settings.minProfit = BigDecimal("8.00")
        // R$ 10 em 4,1 km: R$ 2,44/km (ok), lucro R$ 6,51 (< R$ 8,00) → amarela
        val a = analysis("10.00", "2.2", "1.9")
        val yellow = RideRules(BigDecimal("2.00"), operatingCost = settings.cost, minNetProfit = settings.minProfit)
        val r = record(evaluate(a.offer, yellow))!!
        assertTrue(r.lowProfit)
        assertEquals("🟡 15:00 · Uber · R$ 10,00 · 4,1 km · lucro R$ 6,51", RideHistoryFormatter(zone).row(r, HistoryPeriod.DAY))
        // Corrigindo o valor para R$ 15, o lucro passa de R$ 8 e a corrida fica verde.
        assertEquals(false, history.changeFare(r.id, BigDecimal("15"))!!.lowProfit)
    }
}
