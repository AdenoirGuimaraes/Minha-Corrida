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
 * - movimento menor que [minStepMeters] não conta (GPS "tremendo" parado), mas a HORA da
 *   referência é atualizada: assim, depois de um semáforo ou de esperar o passageiro, a
 *   velocidade é medida desde a última posição parada (antes, a espera entrava no cálculo,
 *   o trecho parecia "a pé" e era perdido — o GPS somava menos que as corridas);
 * - trecho abaixo de [minSpeedMps] (≈ 7 km/h, a pé) não conta, mas a referência anda;
 * - salto acima de [maxSpeedMps] (≈ 200 km/h) é erro do GPS e não conta.
 * Não é thread-safe: um acumulador por serviço.
 */
class DistanceAccumulator(
    private val maxAccuracyMeters: Float = 35f,
    private val minStepMeters: Double = 25.0,
    private val minSpeedMps: Double = 2.0,
    private val maxSpeedMps: Double = 55.0,
) {
    private var anchor: GeoPoint? = null

    /** Último ponto aceito (referência atual); null antes do 1º ponto confiável. */
    val lastPoint: GeoPoint? get() = anchor

    /** @return metros a somar por esta posição (0 se ruído, a pé ou salto). */
    fun add(point: GeoPoint): Double {
        if (point.accuracyMeters > maxAccuracyMeters) return 0.0
        val last = anchor ?: run { anchor = point; return 0.0 }
        val meters = haversineMeters(last, point)
        if (meters < minStepMeters) {
            // Parado (ou quase): mesmo lugar, hora nova.
            if (point.timeMillis > last.timeMillis) anchor = last.copy(timeMillis = point.timeMillis)
            return 0.0
        }
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

/**
 * Início do trajeto que o GPS não pegou (o sensor de veículo leva 1–2 min para perceber o carro e
 * o GPS mais alguns segundos para o 1º ponto confiável): distância em linha reta entre onde o carro
 * ficou estacionado (fim do trajeto anterior) e o 1º ponto do GPS, × [roadFactor] (ruas não são
 * linha reta). Acima de [maxMeters] não estima (outro carro ou dado errado).
 */
object TripStartEstimator {
    const val ROAD_FACTOR = 1.3
    const val MAX_METERS = 3_000.0

    /** @return metros a acrescentar no começo do trajeto (0 sem estacionamento conhecido). */
    fun estimate(parking: GeoPoint?, firstFix: GeoPoint, roadFactor: Double = ROAD_FACTOR, maxMeters: Double = MAX_METERS): Double {
        if (parking == null) return 0.0
        val straight = DistanceAccumulator.haversineMeters(parking, firstFix)
        return if (straight > maxMeters) 0.0 else straight * roadFactor
    }
}

/**
 * Ajuste dos km do GPS pelo painel: entre os tanques cheios, painel ÷ km registrados pelo GPS.
 * Ex.: painel 412 km, GPS 398 km → 1,035 (+3,5%). Só vale com GPS cobrindo ao menos metade do
 * painel (controle ligado na maior parte do tempo) e fica entre 0,8 e 1,3 (proteção).
 */
object KmCorrection {
    private val MIN = BigDecimal("0.80")
    private val MAX = BigDecimal("1.30")

    /** @return fator (3 casas), ou null sem dados suficientes. */
    fun factor(stats: FuelStats, gpsKmInInterval: BigDecimal): BigDecimal? {
        val odometer = stats.odometerKm ?: return null
        if (gpsKmInInterval.signum() <= 0 || gpsKmInInterval * BigDecimal(2) < odometer) return null
        return odometer.divide(gpsKmInInterval, 3, RoundingMode.HALF_UP).max(MIN).min(MAX)
    }

    /** "+3,5%" / "−2,0%" para mostrar ao motorista. */
    fun percentLabel(factor: BigDecimal): String {
        val percent = (factor - BigDecimal.ONE).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
        val sign = if (percent.signum() < 0) "−" else "+"
        return sign + percent.abs().toPlainString().replace('.', ',') + "%"
    }
}

/** Km rodados por dia (somados pelo GPS). */
interface KmLogRepository {
    /** Soma metros no dia; negativo desfaz (trajeto registrado que foi descartado depois). */
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
data class CommuteCost(
    val totalKm: BigDecimal,
    val ridesKm: BigDecimal,
    val commuteKm: BigDecimal,
    val fuelCost: BigDecimal,
    /** Preenchido quando veio da média do painel (sem km de GPS no período): km/dia usados. */
    val averagePerDay: BigDecimal? = null,
    /** Fator do painel aplicado aos km do GPS ([KmCorrection]); null sem ajuste. */
    val correction: BigDecimal? = null,
) {
    companion object {
        /**
         * Sem km de GPS no período: usa a média do painel ([OdometerCommute]) × [days].
         * @return null sem média ou sem dias.
         */
        fun averaged(
            kmPerDay: BigDecimal?,
            days: Long,
            fuelPricePerLiter: BigDecimal?,
            kmPerLiter: BigDecimal?,
        ): CommuteCost? {
            if (kmPerDay == null || kmPerDay.signum() <= 0 || days <= 0) return null
            val commute = (kmPerDay * BigDecimal(days)).setScale(1, RoundingMode.HALF_UP)
            val cost = if (fuelPricePerLiter == null || kmPerLiter == null || kmPerLiter.signum() <= 0) {
                BigDecimal.ZERO.setScale(2)
            } else {
                (commute * fuelPricePerLiter).divide(kmPerLiter, 2, RoundingMode.HALF_UP)
            }
            return CommuteCost(commute, BigDecimal.ZERO.setScale(1), commute, cost, kmPerDay)
        }

        /**
         * @param fuelPricePerLiter/kmPerLiter dos custos do carro; null = sem custo (só os km).
         * @return null se o GPS não somou nada no período.
         */
        fun of(
            gpsKm: BigDecimal,
            ridesKm: BigDecimal,
            fuelPricePerLiter: BigDecimal?,
            kmPerLiter: BigDecimal?,
            correction: BigDecimal? = null,
        ): CommuteCost? {
            if (gpsKm.signum() <= 0) return null
            // Km do GPS ajustados pelo painel (perdas no começo dos trajetos, túneis, sinal fraco).
            val driven = (gpsKm * (correction ?: BigDecimal.ONE)).setScale(1, RoundingMode.HALF_UP)
            val commute = (driven - ridesKm).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP)
            val cost = if (fuelPricePerLiter == null || kmPerLiter == null || kmPerLiter.signum() <= 0) {
                BigDecimal.ZERO.setScale(2)
            } else {
                (commute * fuelPricePerLiter).divide(kmPerLiter, 2, RoundingMode.HALF_UP)
            }
            return CommuteCost(driven, ridesKm.setScale(1, RoundingMode.HALF_UP), commute, cost, correction = correction)
        }
    }
}
