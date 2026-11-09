package br.com.minhacorrida.core.history

import br.com.minhacorrida.core.model.Platform
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Corrida verde (boa) guardada no histórico no momento em que o card apareceu.
 * Os gastos usam os custos do carro configurados NAQUELE momento.
 *
 * @property id identificador único (hora + app + valor).
 * @property timestampMillis quando o card apareceu (epoch, ms).
 * @property fuelCost gasto com combustível (km total × preço do litro ÷ km/l); null se os custos não estavam informados.
 * @property maintenanceCost gasto com manutenção/outros (km total × outros custos/km); null idem.
 * @property done true quando o motorista marcou que REALIZOU a corrida.
 * @property good true = atinge o mínimo R$/km (verde ou amarelo); false = vermelha.
 * @property lowProfit true = amarela: R$/km ok, mas lucro líquido abaixo do mínimo.
 * @property feePercent taxa da plataforma descontada depois (inDrive); 0 na Uber e particular.
 * @property originalFare valor oferecido pelo passageiro, quando o valor foi trocado
 *   (contraproposta no inDrive ou correção à mão); null se nunca mudou.
 */
data class RideRecord(
    val id: String,
    val timestampMillis: Long,
    val platform: Platform,
    val fare: BigDecimal,
    val pickupKm: BigDecimal?,
    val tripKm: BigDecimal,
    val totalKm: BigDecimal,
    val valuePerKm: BigDecimal,
    val fuelCost: BigDecimal?,
    val maintenanceCost: BigDecimal?,
    val done: Boolean = false,
    val good: Boolean = true,
    val originalFare: BigDecimal? = null,
    val feePercent: BigDecimal = BigDecimal.ZERO,
    val lowProfit: Boolean = false,
) {
    /** O que fica com o motorista: valor − taxa da plataforma. */
    val netFare: BigDecimal
        get() = br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase.netOf(fare, feePercent)

    /**
     * A mesma corrida com outro valor (contraproposta aceita ou correção à mão).
     * Km e gastos não mudam (dependem só da distância); R$/km, lucro e cor são recalculados.
     */
    fun withFare(newFare: BigDecimal, minValuePerKm: BigDecimal, minNetProfit: BigDecimal? = null): RideRecord {
        val fare = newFare.setScale(2, RoundingMode.HALF_UP)
        val net = br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase.netOf(fare, feePercent)
        val perKm = net.divide(totalKm, 2, RoundingMode.HALF_UP)
        val updated = copy(
            fare = fare,
            valuePerKm = perKm,
            good = perKm >= minValuePerKm,
            originalFare = originalFare ?: this.fare,
        )
        return updated.copy(
            lowProfit = updated.good && minNetProfit != null && updated.totalCost != null &&
                updated.netProfit < minNetProfit,
        )
    }

    /** Gasto total da corrida (combustível + manutenção); null se os custos não estavam informados. */
    val totalCost: BigDecimal?
        get() = if (fuelCost == null || maintenanceCost == null) null else fuelCost + maintenanceCost

    /** Cor da corrida: vermelha (abaixo do R$/km mínimo), amarela (lucro baixo) ou verde. */
    val rating: br.com.minhacorrida.core.model.RideRating
        get() = when {
            !good -> br.com.minhacorrida.core.model.RideRating.BELOW_MINIMUM
            lowProfit -> br.com.minhacorrida.core.model.RideRating.LOW_PROFIT
            else -> br.com.minhacorrida.core.model.RideRating.GOOD
        }

    /** Lucro real líquido (já sem a taxa da plataforma); sem custos informados, o valor líquido. */
    val netProfit: BigDecimal
        get() = netFare - (totalCost ?: BigDecimal.ZERO)
}
