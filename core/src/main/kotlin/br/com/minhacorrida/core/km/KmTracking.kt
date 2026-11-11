package br.com.minhacorrida.core.km

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Posição do GPS (sem dependência do Android). */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    /** Raio de erro informado pelo GPS, em metros. */
    val accuracyMeters: Float,
    val timeMillis: Long,
)

/**
 * Soma a distância percorrida de CARRO a partir das posições do GPS, filtrando o ruído:
 * - posição com erro maior que [maxAccuracyMeters] é descartada;
 * - movimento menor que [minStepMeters] não conta (GPS "tremendo" parado);
 * - trecho abaixo de [minSpeedMps] (≈ 9 km/h, a pé) não conta, mas a referência anda;
 * - salto acima de [maxSpeedMps] (≈ 200 km/h) é erro do GPS e não conta.
 * Não é thread-safe: um acumulador por serviço.
 */
class DistanceAccumulator(
    private val maxAccuracyMeters: Float = 35f,
    private val minStepMeters: Double = 25.0,
    private val minSpeedMps: Double = 2.5,
    private val maxSpeedMps: Double = 55.0,
) {
    private var anchor: GeoPoint? = null

    /** @return metros a somar por esta posição (0 se ruído, a pé ou salto). */
    fun add(point: GeoPoint): Double {
        if (point.accuracyMeters > maxAccuracyMeters) return 0.0
        val last = anchor ?: run { anchor = point; return 0.0 }
        val meters = haversineMeters(last, point)
        if (meters < minStepMeters) return 0.0
        anchor = point
        val seconds = (point.timeMillis - last.timeMillis) / 1000.0
        if (seconds <= 0) return 0.0
        val speed = meters / seconds
        return if (speed in minSpeedMps..maxSpeedMps) meters else 0.0
    }

    /** Recomeça (ex.: serviço religado depois de horas: não liga o ponto antigo ao novo). */
    fun reset() {
        anchor = null
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(h))
        }
    }
}

/** Km rodados por dia (somados pelo GPS). */
interface KmLogRepository {
    fun addMeters(date: LocalDate, meters: Double)

    /** Km de cada dia do intervalo [start, endExclusive). */
    fun kmBetween(start: LocalDate, endExclusive: LocalDate): BigDecimal

    fun kmOn(date: LocalDate): BigDecimal = kmBetween(date, date.plusDays(1))
}

/** Liga/desliga o controle de km (GPS + abastecimentos). */
interface KmSettingsRepository {
    fun isKmTrackingEnabled(): Boolean
    fun setKmTrackingEnabled(enabled: Boolean)
}

/**
 * Abastecimento anotado pelo motorista (editável: pode anotar depois, com a data certa).
 *
 * @property odometerKm km do painel no momento do abastecimento.
 * @property totalPrice valor pago (R$).
 * @property fullTank true = encheu o tanque (é o que permite calcular o consumo real).
 */
data class FuelEntry(
    val id: String,
    val date: LocalDate,
    val odometerKm: BigDecimal,
    val liters: BigDecimal,
    val totalPrice: BigDecimal,
    val fullTank: Boolean = true,
) {
    init {
        require(odometerKm.signum() >= 0) { "Km do painel inválido" }
        require(liters.signum() > 0) { "Litros devem ser positivos" }
        require(totalPrice.signum() > 0) { "Valor deve ser positivo" }
    }

    /** Preço do litro pago neste abastecimento. */
    val pricePerLiter: BigDecimal get() = totalPrice.divide(liters, 2, RoundingMode.HALF_UP)
}

interface FuelLogRepository {
    /** Todos, do mais recente para o mais antigo. */
    fun all(): List<FuelEntry>

    /** Cria ou substitui (mesmo id). */
    fun save(entry: FuelEntry)

    fun delete(id: String)
}

/**
 * Consumo real pelo método do tanque cheio: entre dois abastecimentos de tanque cheio,
 * km rodados (diferença do painel) ÷ litros colocados depois do primeiro (até o segundo, inclusive).
 *
 * @property kmPerLiter consumo real médio (null com menos de 2 tanques cheios).
 * @property pricePerLiter preço médio pago (todos os abastecimentos), ou null sem nenhum.
 * @property odometerKm km do painel entre o primeiro e o último tanque cheio usados no cálculo.
 * @property from/to datas desse intervalo (para comparar com o km do GPS).
 */
data class FuelStats(
    val kmPerLiter: BigDecimal?,
    val pricePerLiter: BigDecimal?,
    val odometerKm: BigDecimal?,
    val from: LocalDate?,
    val to: LocalDate?,
)

object FuelStatsCalculator {

    fun of(entries: List<FuelEntry>): FuelStats {
        val price = entries.takeIf { it.isNotEmpty() }?.let { list ->
            list.fold(BigDecimal.ZERO) { acc, e -> acc + e.totalPrice }
                .divide(list.fold(BigDecimal.ZERO) { acc, e -> acc + e.liters }, 2, RoundingMode.HALF_UP)
        }
        // Ordem do painel: o km só cresce, mesmo que a data tenha sido anotada depois.
        val byOdometer = entries.sortedBy { it.odometerKm }
        val firstFull = byOdometer.indexOfFirst { it.fullTank }
        val lastFull = byOdometer.indexOfLast { it.fullTank }
        if (firstFull < 0 || lastFull <= firstFull) return FuelStats(null, price, null, null, null)

        val km = byOdometer[lastFull].odometerKm - byOdometer[firstFull].odometerKm
        val liters = byOdometer.subList(firstFull + 1, lastFull + 1).fold(BigDecimal.ZERO) { acc, e -> acc + e.liters }
        if (km.signum() <= 0 || liters.signum() <= 0) return FuelStats(null, price, null, null, null)
        return FuelStats(
            kmPerLiter = km.divide(liters, 1, RoundingMode.HALF_UP),
            pricePerLiter = price,
            odometerKm = km,
            from = byOdometer[firstFull].date,
            to = byOdometer[lastFull].date,
        )
    }
}

/**
 * Deslocamento sem corrida: km rodados (GPS) − km das corridas realizadas, e a gasolina dele.
 * Ex.: rodou 52,3 km, corridas 17,7 km → 34,6 km × R$ 6,30 ÷ 10,5 km/L = R$ 20,76.
 */
data class CommuteCost(val totalKm: BigDecimal, val ridesKm: BigDecimal, val commuteKm: BigDecimal, val fuelCost: BigDecimal) {
    companion object {
        /**
         * @param fuelPricePerLiter/kmPerLiter dos custos do carro; null = sem custo (só os km).
         * @return null se o GPS não somou nada no período.
         */
        fun of(
            gpsKm: BigDecimal,
            ridesKm: BigDecimal,
            fuelPricePerLiter: BigDecimal?,
            kmPerLiter: BigDecimal?,
        ): CommuteCost? {
            if (gpsKm.signum() <= 0) return null
            val commute = (gpsKm - ridesKm).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP)
            val cost = if (fuelPricePerLiter == null || kmPerLiter == null || kmPerLiter.signum() <= 0) {
                BigDecimal.ZERO.setScale(2)
            } else {
                (commute * fuelPricePerLiter).divide(kmPerLiter, 2, RoundingMode.HALF_UP)
            }
            return CommuteCost(gpsKm.setScale(1, RoundingMode.HALF_UP), ridesKm.setScale(1, RoundingMode.HALF_UP), commute, cost)
        }
    }
}
