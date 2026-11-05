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
 * @property good true = estava verde (atinge o mínimo R$/km); false = vermelha (pedido do inDrive aberto).
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
) {
    /**
     * A mesma corrida com outro valor (contraproposta aceita ou correção à mão).
     * Km e gastos não mudam (dependem só da distância); R$/km, lucro e cor são recalculados.
     */
    fun withFare(newFare: BigDecimal, minValuePerKm: BigDecimal): RideRecord {
        val fare = newFare.setScale(2, RoundingMode.HALF_UP)
        val perKm = fare.divide(totalKm, 2, RoundingMode.HALF_UP)
        return copy(
            fare = fare,
            valuePerKm = perKm,
            good = perKm >= minValuePerKm,
            originalFare = originalFare ?: this.fare,
        )
    }

    /** Gasto total da corrida (combustível + manutenção); null se os custos não estavam informados. */
    val totalCost: BigDecimal?
        get() = if (fuelCost == null || maintenanceCost == null) null else fuelCost + maintenanceCost

    /** Lucro real líquido; sem custos informados, o valor bruto da corrida. */
    val netProfit: BigDecimal
        get() = fare - (totalCost ?: BigDecimal.ZERO)
}
