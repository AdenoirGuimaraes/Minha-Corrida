package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideHistoryFormatter
import br.com.minhacorrida.core.goal.DailyGoalCalculator
import br.com.minhacorrida.core.goal.ProfitDeduction
import br.com.minhacorrida.core.history.DateRange
import br.com.minhacorrida.core.history.HistoryPeriod
import br.com.minhacorrida.core.history.RideHistoryPage
import br.com.minhacorrida.core.history.RideHistorySummary
import br.com.minhacorrida.core.km.CommuteCost
import br.com.minhacorrida.core.km.DistanceAccumulator
import br.com.minhacorrida.core.km.FuelEntry
import br.com.minhacorrida.core.km.FuelStatsCalculator
import br.com.minhacorrida.core.km.GeoPoint
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedido de 10/11: km rodados no dia (GPS) + tanque cheio para o consumo real. */
class KmTrackingTest {
    // Lages: 0,001° de latitude ≈ 111 m.
    private fun p(lat: Double, seconds: Long, accuracy: Float = 8f) = GeoPoint(lat, -50.33, accuracy, seconds * 1000)

    @Test
    fun `soma trechos de carro e ignora parado, a pe, imprecisao e saltos`() {
        val acc = DistanceAccumulator()
        assertEquals(0.0, acc.add(p(-27.800, 0)))                       // 1º ponto
        val carro = acc.add(p(-27.801, 10))                             // 111 m em 10 s = 40 km/h
        assertTrue(carro in 105.0..117.0)
        assertEquals(0.0, acc.add(p(-27.80105, 20)))                    // 5 m: parado no semáforo
        assertEquals(0.0, acc.add(p(-27.802, 120)))                     // 105 m em 100 s: a pé
        assertEquals(0.0, acc.add(p(-27.900, 130)))                     // 11 km em 10 s: erro do GPS
        assertEquals(0.0, acc.add(p(-27.901, 140, accuracy = 80f)))     // impreciso: descartado
        assertTrue(acc.add(p(-27.901, 150)) > 100.0)                    // volta a contar
    }

    @Test
    fun `pedido de 12-11 - depois do semaforo o trecho conta (antes a espera fazia parecer a pe)`() {
        val acc = DistanceAccumulator()
        acc.add(p(-27.800, 0))
        assertTrue(acc.add(p(-27.801, 10)) > 100.0)                     // andando
        for (t in 14L..74L step 4) assertEquals(0.0, acc.add(p(-27.80101, t)))  // 60 s no semáforo
        val saiu = acc.add(p(-27.80155, 80))                            // 60 m em 6 s depois do verde
        assertTrue(saiu in 55.0..65.0, "trecho depois do semáforo: $saiu")
    }

    private fun entry(id: String, day: Int, odo: String, liters: String, price: String, full: Boolean = true) =
        FuelEntry(id, LocalDate.of(2026, 11, day), BigDecimal(odo), BigDecimal(liters), BigDecimal(price), full)

    @Test
    fun `consumo real pelo tanque cheio usa os litros depois do primeiro tanque cheio`() {
        val stats = FuelStatsCalculator.of(
            listOf(
                entry("c", 9, "45500", "20", "126.00", full = false),   // meio tanque no meio do caminho
                entry("a", 1, "45000", "40", "250.00"),
                entry("d", 12, "45840", "60", "378.00"),
            ),
        )
        assertEquals("10.5", stats.kmPerLiter!!.toPlainString())        // 840 km ÷ (20 + 60) L
        assertEquals("6.28", stats.pricePerLiter!!.toPlainString())     // 754 ÷ 120 L
        assertEquals("840", stats.odometerKm!!.toPlainString())
        assertEquals(LocalDate.of(2026, 11, 1), stats.from)
    }

    @Test
    fun `um tanque cheio so nao calcula consumo e dados invalidos sao recusados`() {
        assertNull(FuelStatsCalculator.of(listOf(entry("a", 1, "45000", "40", "250.00"))).kmPerLiter)
        assertFailsWith<IllegalArgumentException> { entry("x", 1, "45000", "0", "250.00") }
        assertFailsWith<IllegalArgumentException> { entry("x", 1, "45000", "40", "0") }
    }

    @Test
    fun `deslocamento sem corrida entra no Separar e sai do que fica com voce`() {
        val commute = CommuteCost.of(BigDecimal("52.3"), BigDecimal("17.7"), BigDecimal("6.30"), BigDecimal("10.5"))!!
        assertEquals("34.6", commute.commuteKm.toPlainString())
        assertEquals("20.76", commute.fuelCost.toPlainString())          // 34,6 × 6,30 ÷ 10,5

        val day = LocalDate.of(2026, 11, 10)
        val page = RideHistoryPage(
            HistoryPeriod.DAY, DateRange(day, day.plusDays(1)), emptyList(),
            RideHistorySummary(5, BigDecimal("17.7"), BigDecimal("59.70"), BigDecimal("10.87"), BigDecimal("5.31"), BigDecimal("43.52"), 0),
        )
        val progress = DailyGoalCalculator.progress(
            BigDecimal("43.52") - commute.fuelCost, listOf(ProfitDeduction("Dízimo", BigDecimal("10"))), null,
        )
        val f = RideHistoryFormatter()
        assertEquals("R$ 20,48", f.kept(progress))                       // (43,52 − 20,76) − 10%
        assertEquals("R$ 39,22", f.setAside(page, progress, commute))    // 10,87 + 5,31 + 2,28 + 20,76
        assertTrue(f.setAsideDetails(page, progress, commute).contains("🚗 Gasolina do deslocamento 34,6 km (rodou 52,3 km) R$ 20,76"))
        // Pedido de 11/11: corridas pagaram os próprios custos? E a gasolina do dia inteiro?
        assertEquals(
            "✅ Corridas pagaram os próprios custos (R$ 16,18) e sobraram R$ 43,52\n" +
                "⛽ Gasolina do dia (52,3 km) R$ 31,63: ✅ coberta pelo recebido",
            f.coverage(page, commute),
        )
    }

    @Test
    fun `poucas corridas - mostra o que faltou para cobrir os custos e a gasolina do dia`() {
        val day = LocalDate.of(2026, 11, 11)
        val page = RideHistoryPage(
            HistoryPeriod.DAY, DateRange(day, day.plusDays(1)), emptyList(),
            // 1 corrida: R$ 8,00 recebidos, 4,1 km, gastos 2,46 + 1,03.
            RideHistorySummary(1, BigDecimal("4.1"), BigDecimal("8.00"), BigDecimal("2.46"), BigDecimal("1.03"), BigDecimal("4.51"), 0),
        )
        val commute = CommuteCost.of(BigDecimal("30.0"), BigDecimal("4.1"), BigDecimal("6.30"), BigDecimal("10.5"))!!
        assertEquals(
            "✅ Corridas pagaram os próprios custos (R$ 3,49) e sobraram R$ 4,51\n" +
                "⛽ Gasolina do dia (30 km) R$ 18,00: ⚠️ faltam R$ 10,00 de corrida para cobrir",
            RideHistoryFormatter().coverage(page, commute),
        )
    }

    @Test
    fun `GPS que somou menos que as corridas nao gera deslocamento negativo e sem GPS nada aparece`() {
        assertEquals("0.0", CommuteCost.of(BigDecimal("10"), BigDecimal("17.7"), null, null)!!.commuteKm.toPlainString())
        assertNull(CommuteCost.of(BigDecimal.ZERO, BigDecimal("17.7"), BigDecimal("6.30"), BigDecimal("10.5")))
    }
}
