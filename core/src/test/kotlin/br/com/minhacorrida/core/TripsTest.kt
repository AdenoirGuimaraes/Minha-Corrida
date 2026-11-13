package br.com.minhacorrida.core

import br.com.minhacorrida.core.km.CommuteCost
import br.com.minhacorrida.core.km.FuelStats
import br.com.minhacorrida.core.km.KmLogRepository
import br.com.minhacorrida.core.km.OdometerCommute
import br.com.minhacorrida.core.km.Trip
import br.com.minhacorrida.core.km.TripOutcome
import br.com.minhacorrida.core.km.TripRepository
import br.com.minhacorrida.core.km.TripStatus
import br.com.minhacorrida.core.km.TripUseCase
import br.com.minhacorrida.core.port.NoOpLogger
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedido de 10/11: cada trajeto pergunta Registrar/Descartar (pode ser o carro do trabalho). */
class TripsTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val start = LocalDate.of(2026, 11, 11).atTime(7, 42).atZone(zone).toInstant().toEpochMilli()
    private val end = start + 23 * 60_000

    private class FakeTrips : TripRepository {
        val map = linkedMapOf<String, Trip>()
        override fun save(trip: Trip) { map[trip.id] = trip }
        override fun find(id: String) = map[id]
        override fun pending() = map.values.filter { it.status == TripStatus.PENDING }.sortedBy { it.startMillis }
        override fun all() = map.values.toList()
    }

    private class FakeKmLog : KmLogRepository {
        val days = mutableMapOf<LocalDate, Double>()
        override fun addMeters(date: LocalDate, meters: Double) { days[date] = (days[date] ?: 0.0) + meters }
        override fun kmBetween(start: LocalDate, endExclusive: LocalDate): BigDecimal =
            BigDecimal(days.filterKeys { it >= start && it < endExclusive }.values.sum() / 1000).setScale(1, java.math.RoundingMode.HALF_UP)
    }

    private val trips = FakeTrips()
    private val kmLog = FakeKmLog()
    private val useCase = TripUseCase(trips, kmLog, NoOpLogger, zone)
    private val day = LocalDate.of(2026, 11, 11)

    @Test
    fun `trajeto sem corrida fica pendente e so soma ao registrar`() {
        val outcome = assertIs<TripOutcome.NeedsDecision>(useCase.finish("t1", start, end, 12_400.0, hadRide = false))
        assertEquals("12.4", outcome.trip.km.toPlainString())
        assertEquals(BigDecimal("0.0"), kmLog.kmOn(day))
        assertEquals(1, useCase.pending().size)

        useCase.keep("t1")
        assertEquals(BigDecimal("12.4"), kmLog.kmOn(day))
        assertTrue(useCase.pending().isEmpty())
        assertNull(useCase.keep("t1"))                                  // decidir de novo não soma 2x
        assertEquals(BigDecimal("12.4"), kmLog.kmOn(day))
    }

    @Test
    fun `descartar ignora o trajeto (carro do trabalho)`() {
        useCase.finish("t2", start, end, 8_000.0, hadRide = false)
        useCase.discard("t2")
        assertEquals(BigDecimal("0.0"), kmLog.kmOn(day))
        assertEquals(TripStatus.DISCARDED, trips.find("t2")!!.status)
    }

    @Test
    fun `trajeto com corrida e registrado sozinho e trajeto curto e ignorado`() {
        assertIs<TripOutcome.AutoKept>(useCase.finish("t3", start, end, 21_000.0, hadRide = true))
        assertEquals(BigDecimal("21.0"), kmLog.kmOn(day))
        assertIs<TripOutcome.TooShort>(useCase.finish("t4", start, end, 300.0, hadRide = false))
        assertTrue(useCase.pending().isEmpty())
    }

    @Test
    fun `media do painel - 412 km em 7 dias com 146 km de corrida da 38 km por dia`() {
        val stats = FuelStats(BigDecimal("10.5"), BigDecimal("6.30"), BigDecimal("412"), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 8))
        val perDay = OdometerCommute.kmPerDay(stats, BigDecimal("146"))!!
        assertEquals("38.0", perDay.toPlainString())
        val commute = CommuteCost.averaged(perDay, 1, BigDecimal("6.30"), BigDecimal("10.5"))!!
        assertEquals("22.80", commute.fuelCost.toPlainString())        // 38 × 6,30 ÷ 10,5
        assertNull(OdometerCommute.kmPerDay(FuelStats(null, null, null, null, null), BigDecimal.ZERO))
    }

    @Test
    fun `pedido de 12-11 - trajetos do dia aparecem na lista e da para trocar de ideia`() {
        useCase.finish("ida", start, end, 4_600.0, hadRide = false)
        useCase.keep("ida")
        useCase.finish("volta", start + 9 * 3_600_000, end + 9 * 3_600_000, 4_400.0, hadRide = false)
        useCase.finish("corrida", start + 12 * 3_600_000, end + 12 * 3_600_000, 8_000.0, hadRide = true)

        val list = useCase.onDay(day)
        assertEquals(listOf("ida", "volta", "corrida"), list.map { it.id })
        assertEquals("✅ 4,6 km · 07:42–08:05 · registrado", br.com.minhacorrida.core.km.TripListFormatter.row(list[0], zone))
        assertEquals("⏳ 4,4 km · 16:42–17:05 · esperando você decidir", br.com.minhacorrida.core.km.TripListFormatter.row(list[1], zone))
        assertEquals("✅ 8,0 km · 19:42–20:05 · com corrida", br.com.minhacorrida.core.km.TripListFormatter.row(list[2], zone))
        assertEquals(
            "🚗 Trajetos do dia: 3 · 12,6 km registrados · toque para ver",
            br.com.minhacorrida.core.km.TripListFormatter.summary(list),
        )
        assertEquals(BigDecimal("12.6"), kmLog.kmOn(day))

        // Registrou a ida sem querer: descartar tira os km; registrar de novo devolve.
        useCase.change("ida", TripStatus.DISCARDED)
        assertEquals(BigDecimal("8.0"), kmLog.kmOn(day))
        useCase.change("ida", TripStatus.DISCARDED)                    // repetir não tira 2x
        assertEquals(BigDecimal("8.0"), kmLog.kmOn(day))
        useCase.change("volta", TripStatus.KEPT)                       // pendente → registrado
        assertEquals(BigDecimal("12.4"), kmLog.kmOn(day))
        assertNull(useCase.change("volta", TripStatus.PENDING))
        assertNull(useCase.change("nao-existe", TripStatus.KEPT))
    }

    @Test
    fun `pedido de 12-11 - km das corridas no app x km do GPS`() {
        val h = 3_600_000L
        useCase.finish("trabalho", start, end, 3_700.0, hadRide = false); useCase.keep("trabalho")
        useCase.finish("t1", start + 10 * h, start + 11 * h, 8_000.0, hadRide = true)
        useCase.finish("t2", start + 12 * h, start + 13 * h, 11_900.0, hadRide = true)
        val rides = listOf(
            start + 10 * h + 60_000 to BigDecimal("4.2"),
            start + 10 * h + 1_800_000 to BigDecimal("5.0"),
            start + 12 * h - 300_000 to BigDecimal("12.1"),         // aceitou parado: vai para o t2
            start + 15 * h to BigDecimal("3.0"),                    // sem trajeto (GPS não ligou)
        )
        val check = br.com.minhacorrida.core.km.RideKmComparison.check(useCase.onDay(day), rides)
        assertEquals(2, check.perTrip["t1"]!!.count)
        assertEquals(BigDecimal("9.2"), check.perTrip["t1"]!!.km)
        assertEquals(BigDecimal("12.1"), check.perTrip["t2"]!!.km)
        assertNull(check.perTrip["trabalho"])                         // trajeto do trabalho não entra
        assertEquals(BigDecimal("24.3"), check.ridesKm)
        assertEquals(BigDecimal("19.9"), check.gpsKm)
        assertEquals(1, check.outside)
        assertEquals(
            "📏 Corridas: 24,3 km no app × 19,9 km no GPS (−4,4 km · −18%) ⚠️ GPS somou menos\n" +
                "1 corrida(s) fora de trajeto (GPS não ligou)",
            br.com.minhacorrida.core.km.RideKmComparison.summary(check),
        )
        val t1 = useCase.onDay(day).first { it.id == "t1" }
        assertEquals(
            "✅ 8,0 km · 17:42–18:42 · com corrida\n     2 corrida(s): 9,2 km no app",
            br.com.minhacorrida.core.km.TripListFormatter.row(t1, zone, check.perTrip["t1"]),
        )
    }
}
