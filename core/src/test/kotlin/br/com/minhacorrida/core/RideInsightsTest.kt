package br.com.minhacorrida.core

import br.com.minhacorrida.core.history.RideRecord
import br.com.minhacorrida.core.insights.RideInsights
import br.com.minhacorrida.core.model.Platform
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedido de 13/11 (itens 6 e 8): melhores horários, dias da semana e Uber × inDrive. */
class RideInsightsTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    private fun ride(
        id: String, at: LocalDateTime, platform: Platform, fare: String, km: String,
        fuel: String, maintenance: String, minutes: Int?, done: Boolean = true, fee: String = "0",
    ) = RideRecord(
        id = id,
        timestampMillis = at.atZone(zone).toInstant().toEpochMilli(),
        platform = platform,
        fare = BigDecimal(fare).setScale(2), // como o app grava (2 casas)
        pickupKm = null,
        tripKm = BigDecimal(km),
        totalKm = BigDecimal(km),
        valuePerKm = BigDecimal("2.00"),
        fuelCost = BigDecimal(fuel),
        maintenanceCost = BigDecimal(maintenance),
        done = done,
        feePercent = BigDecimal(fee),
        minutes = minutes,
    )

    private val friday = LocalDateTime.of(2026, 11, 13, 19, 0)
    private val rides = listOf(
        ride("r1", friday, Platform.UBER, "20", "8", "4", "1", 20),
        ride("r2", friday.plusMinutes(30), Platform.UBER, "10", "5", "2.5", "0.5", 10),
        ride("r3", friday.plusHours(1), Platform.INDRIVE, "15", "6", "3", "1", 15, fee = "10"),
        ride("r4", LocalDateTime.of(2026, 11, 9, 8, 0), Platform.UBER, "8", "6", "3", "0.6", null),
        ride("r5", friday.plusHours(2).plusMinutes(30), Platform.UBER, "50", "5", "2", "1", 10, done = false),
    )

    @Test
    fun `melhor horario e o de maior R$ por km com amostra suficiente`() {
        val bands = RideInsights.byTimeBand(rides, zone)
        assertEquals(listOf("06h–09h", "18h–21h"), bands.map { it.label })     // não realizada fica de fora
        val night = bands.last()
        assertEquals(3, night.rides)
        assertEquals("2.29", night.perKm.toPlainString())                     // 43,50 ÷ 19 km
        assertEquals("10.50", night.avgProfit!!.toPlainString())              // (15 + 7 + 9,50) ÷ 3
        assertEquals("42.00", night.profitPerHour!!.toPlainString())          // 31,50 em 45 min
        assertEquals(night, RideInsights.best(bands))
        assertNull(bands.first().profitPerHour)                               // sem minutos guardados
    }

    @Test
    fun `dia da semana e Uber x inDrive com a taxa paga`() {
        assertEquals(listOf("Segunda", "Sexta"), RideInsights.byWeekday(rides, zone).map { it.label })
        val (uber, indrive) = RideInsights.byPlatform(rides)
        assertEquals("2.00", uber.perKm.toPlainString())                       // 38 ÷ 19 km
        assertEquals("2.25", indrive.perKm.toPlainString())                    // 13,50 ÷ 6 km
        assertEquals("1.50", indrive.feePaid.toPlainString())
        assertNull(RideInsights.best(listOf(indrive)))                         // 1 corrida: pouca amostra
    }

    @Test
    fun `texto da janela Analises`() {
        val text = RideInsights.text(rides, zone, "Novembro/2026")
        assertTrue(text.startsWith("Novembro/2026 · 4 corrida(s) realizada(s)"))
        assertTrue(text.contains("18h–21h ⭐: 3 corr. · R$ 2,29/km · lucro méd. R$ 10,50 · R$ 42,00/h"))
        assertTrue(text.contains("inDrive: 1 corr. · R$ 2,25/km · lucro méd. R$ 9,50 · R$ 38,00/h · taxa R$ 1,50"))
        assertEquals("Nenhuma corrida realizada em Hoje.", RideInsights.text(emptyList(), zone, "Hoje"))
    }
}
