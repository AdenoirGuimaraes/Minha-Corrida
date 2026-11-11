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
