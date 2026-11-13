package br.com.minhacorrida.core.km

import br.com.minhacorrida.core.port.AppLogger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Situação de um trajeto de carro detectado pelo sensor de veículo. */
enum class TripStatus {
    /** Esperando o motorista: Registrar ou Descartar (ex.: pode ser o carro do trabalho). */
    PENDING,

    /** Somado aos km do dia. */
    KEPT,

    /** Ignorado (não entra em conta nenhuma). */
    DISCARDED,
}

/**
 * Trajeto: do momento em que o sensor detectou "em veículo" até sair dele.
 *
 * @property meters distância somada pelo GPS no trajeto.
 * @property withRide true se alguma corrida foi confirmada durante o trajeto (é o carro do motorista).
 * @property estimatedMeters parte de [meters] estimada no começo ([TripStartEstimator]).
 */
data class Trip(
    val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val meters: Double,
    val status: TripStatus,
    val withRide: Boolean = false,
    val estimatedMeters: Double = 0.0,
) {
    val km: BigDecimal get() = BigDecimal(meters).divide(BigDecimal(1000), 1, RoundingMode.HALF_UP)

    fun date(zone: ZoneId): LocalDate = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
}

interface TripRepository {
    fun save(trip: Trip)
    fun find(id: String): Trip?

    /** Pendentes, do mais antigo para o mais recente. */
    fun pending(): List<Trip>

    /** Todos os trajetos guardados (registrados, descartados e pendentes). */
    fun all(): List<Trip>
}

/** O que aconteceu com o trajeto que acabou de terminar. */
sealed class TripOutcome {
    /** Curto demais (manobra, troca de vaga): ignorado sem perguntar. */
    data class TooShort(val trip: Trip) : TripOutcome()

    /** Houve corrida no trajeto: registrado sozinho. */
    data class AutoKept(val trip: Trip) : TripOutcome()

    /** Precisa da decisão do motorista (notificação Registrar/Descartar). */
    data class NeedsDecision(val trip: Trip) : TripOutcome()
}

/**
 * Decide o destino de cada trajeto e aplica a decisão do motorista.
 * Só trajetos REGISTRADOS somam nos km do dia (data do início do trajeto).
 */
class TripUseCase(
    private val trips: TripRepository,
    private val kmLog: KmLogRepository,
    private val logger: AppLogger,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val minMeters: Double = MIN_TRIP_METERS,
) {
    /**
     * Fim do trajeto.
     * @param hadRide true se alguma corrida foi confirmada entre [startMillis] e [endMillis].
     */
    fun finish(
        id: String,
        startMillis: Long,
        endMillis: Long,
        meters: Double,
        hadRide: Boolean,
        estimatedMeters: Double = 0.0,
    ): TripOutcome {
        val base = Trip(id, startMillis, endMillis, meters, TripStatus.PENDING, hadRide, estimatedMeters)
        return try {
            when {
                meters < minMeters -> TripOutcome.TooShort(base.copy(status = TripStatus.DISCARDED))
                hadRide -> {
                    val kept = base.copy(status = TripStatus.KEPT)
                    trips.save(kept)
                    kmLog.addMeters(kept.date(zone), meters)
                    TripOutcome.AutoKept(kept)
                }
                else -> {
                    trips.save(base)
                    TripOutcome.NeedsDecision(base)
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao fechar o trajeto", e)
            TripOutcome.NeedsDecision(base)
        }
    }

    /** "Registrar": soma os km na data do trajeto. @return o trajeto, ou null se não estava pendente. */
    fun keep(id: String): Trip? = decide(id, TripStatus.KEPT)

    /** "Descartar": ignora o trajeto. */
    fun discard(id: String): Trip? = decide(id, TripStatus.DISCARDED)

    fun pending(): List<Trip> = try {
        trips.pending()
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao ler trajetos pendentes", e)
        emptyList()
    }

    /** Trajetos que começaram em [date] (registrados, descartados e pendentes), em ordem de hora. */
    fun onDay(date: LocalDate): List<Trip> = try {
        trips.all().filter { it.date(zone) == date }.sortedBy { it.startMillis }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao ler os trajetos do dia", e)
        emptyList()
    }

    /**
     * Trocar de ideia depois de decidir: registrado ↔ descartado. Os km do dia acompanham
     * (descartar um registrado tira os km; registrar um descartado soma).
     * @return o trajeto com a nova situação, ou null se não existir ou [status] for PENDING.
     */
    fun change(id: String, status: TripStatus): Trip? = try {
        val trip = trips.find(id)
        when {
            trip == null || status == TripStatus.PENDING -> null
            trip.status == status -> trip
            else -> trip.copy(status = status).also { changed ->
                trips.save(changed)
                val date = changed.date(zone)
                if (status == TripStatus.KEPT) kmLog.addMeters(date, changed.meters)
                if (trip.status == TripStatus.KEPT) kmLog.addMeters(date, -changed.meters)
                logger.debug(TAG, "Trajeto ${changed.km} km: ${trip.status} → $status")
            }
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao trocar a decisão do trajeto $id", e)
        null
    }

    private fun decide(id: String, status: TripStatus): Trip? = try {
        val trip = trips.find(id)?.takeIf { it.status == TripStatus.PENDING }
        trip?.copy(status = status)?.also { decided ->
            trips.save(decided)
            if (status == TripStatus.KEPT) kmLog.addMeters(decided.date(zone), decided.meters)
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao decidir o trajeto $id", e)
        null
    }

    companion object {
        private const val TAG = "Trips"
        const val MIN_TRIP_METERS = 500.0
    }
}

/**
 * Confere os km das corridas (o que o Uber/inDrive informou) com os km do GPS.
 * Cada corrida realizada vai para o trajeto em que foi registrada; se foi registrada com o
 * carro parado (entre trajetos), vai para o próximo trajeto que começou até [GRACE_MS] depois.
 */
object RideKmComparison {
    const val GRACE_MS = 15 * 60_000L

    /** Km das corridas de um trajeto. */
    data class TripRides(val count: Int, val km: BigDecimal)

    /** Resultado do dia: por trajeto, os totais e as corridas que não caíram em trajeto nenhum. */
    data class DayCheck(
        val perTrip: Map<String, TripRides>,
        val ridesKm: BigDecimal,
        val gpsKm: BigDecimal,
        val outside: Int,
    ) {
        /** GPS − corridas (negativo: o GPS somou menos). */
        val differenceKm: BigDecimal get() = gpsKm - ridesKm
    }

    /**
     * @param trips trajetos do dia (só os registrados entram no GPS).
     * @param rides corridas REALIZADAS do dia (timestamp, km total do app).
     */
    fun check(trips: List<Trip>, rides: List<Pair<Long, BigDecimal>>): DayCheck {
        val kept = trips.filter { it.status == TripStatus.KEPT }.sortedBy { it.startMillis }
        val perTrip = mutableMapOf<String, TripRides>()
        var outside = 0
        var ridesKm = BigDecimal.ZERO
        for ((at, km) in rides) {
            ridesKm += km
            val trip = kept.firstOrNull { at in it.startMillis..it.endMillis }
                ?: kept.firstOrNull { it.startMillis in at..(at + GRACE_MS) }
            if (trip == null) {
                outside++
                continue
            }
            val current = perTrip[trip.id] ?: TripRides(0, BigDecimal.ZERO)
            perTrip[trip.id] = TripRides(current.count + 1, current.km + km)
        }
        // GPS só dos trajetos onde houve corrida: os do trabalho/particulares não entram na conta.
        val gps = kept.filter { it.id in perTrip }.fold(BigDecimal.ZERO) { acc, t -> acc + t.km }
        return DayCheck(perTrip, ridesKm.setScale(1, RoundingMode.HALF_UP), gps.setScale(1, RoundingMode.HALF_UP), outside)
    }

    /**
     * "📏 Corridas: 45,4 km no app × 38,1 km no GPS (−7,3 km · −16%)" + aviso das corridas fora de trajeto.
     * @return null sem corridas no dia.
     */
    fun summary(check: DayCheck): String? {
        if (check.ridesKm.signum() == 0) return null
        val diff = check.differenceKm
        val percent = if (check.ridesKm.signum() > 0) {
            diff.multiply(BigDecimal(100)).divide(check.ridesKm, 0, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
        val verdict = when {
            percent.abs() <= BigDecimal(5) -> "✅ bate"
            diff.signum() < 0 -> "⚠️ GPS somou menos"
            else -> "GPS somou mais (inclui ir até o passageiro e voltas sem corrida)"
        }
        val line = "📏 Corridas: ${km(check.ridesKm)} km no app × ${km(check.gpsKm)} km no GPS " +
            "(${signed(diff)} km · ${signed(percent)}%) $verdict"
        return if (check.outside > 0) "$line\n${check.outside} corrida(s) fora de trajeto (GPS não ligou)" else line
    }

    private fun km(value: BigDecimal) = value.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
    private fun signed(value: BigDecimal): String {
        val text = value.abs().toPlainString().replace('.', ',')
        return when (value.signum()) { -1 -> "−$text"; 1 -> "+$text"; else -> text }
    }
}

/**
 * Textos da lista "Trajetos do dia" na aba Corridas.
 * Ex.: "✅ 4,6 km · 07:42–08:05 · com corrida", "❌ 9,1 km · 12:10–12:31 · descartado".
 */
object TripListFormatter {
    private val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    /** @param rides corridas do trajeto (comparação com o GPS), se houver. */
    fun row(trip: Trip, zone: ZoneId, rides: RideKmComparison.TripRides? = null): String {
        val start = Instant.ofEpochMilli(trip.startMillis).atZone(zone).format(TIME)
        val end = Instant.ofEpochMilli(trip.endMillis).atZone(zone).format(TIME)
        val (icon, label) = when (trip.status) {
            TripStatus.KEPT -> "✅" to if (trip.withRide) "com corrida" else "registrado"
            TripStatus.DISCARDED -> "❌" to "descartado"
            TripStatus.PENDING -> "⏳" to "esperando você decidir"
        }
        val base = "$icon ${km(trip.km)} km · $start–$end · $label"
        return if (rides == null || trip.status != TripStatus.KEPT) {
            base
        } else {
            "$base\n     ${rides.count} corrida(s): ${km(rides.km)} km no app"
        }
    }

    /** Linha do resumo: "🚗 Trajetos do dia: 6 · 49,2 km registrados · toque para ver". */
    fun summary(trips: List<Trip>): String? {
        if (trips.isEmpty()) return null
        val kept = trips.filter { it.status == TripStatus.KEPT }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.km }
        return "🚗 Trajetos do dia: ${trips.size} · ${km(kept)} km registrados · toque para ver"
    }

    private fun km(value: BigDecimal) = value.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}

/**
 * Deslocamento médio pelo painel (sem GPS): entre dois tanques cheios, km do painel − km das
 * corridas realizadas no mesmo intervalo, dividido pelos dias. Só conta o carro do motorista.
 * Ex.: 412 km no painel em 7 dias, corridas 146 km → 38 km/dia de deslocamento.
 */
object OdometerCommute {
    /**
     * @return km/dia (1 casa), ou null sem consumo real calculado ou com intervalo vazio.
     */
    fun kmPerDay(stats: FuelStats, ridesKm: BigDecimal): BigDecimal? {
        val odometer = stats.odometerKm ?: return null
        val from = stats.from ?: return null
        val to = stats.to ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to).coerceAtLeast(1)
        val commute = (odometer - ridesKm).max(BigDecimal.ZERO)
        return commute.divide(BigDecimal(days), 1, RoundingMode.HALF_UP)
    }
}
