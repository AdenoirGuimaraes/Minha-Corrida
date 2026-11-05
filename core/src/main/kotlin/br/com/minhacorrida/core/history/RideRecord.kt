package br.com.minhacorrida.core.history

import br.com.minhacorrida.core.model.Platform
import java.math.BigDecimal

/**
 * Corrida verde (boa) guardada no histórico no momento em que o card apareceu.
 * Os gastos usam os custos do carro configurados NAQUELE momento.
 *
 * @property id identificador único (hora + app + valor).
 * @property timestampMillis quando o card apareceu (epoch, ms).
 * @property fuelCost gasto com combustível (km total × preço do litro ÷ km/l); null se os custos não estavam informados.
 * @property maintenanceCost gasto com manutenção/outros (km total × outros custos/km); null idem.
 * @property done true quando o motorista marcou que REALIZOU a corrida.
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
) {
    /** Gasto total da corrida (combustível + manutenção); null se os custos não estavam informados. */
    val totalCost: BigDecimal?
        get() = if (fuelCost == null || maintenanceCost == null) null else fuelCost + maintenanceCost

    /** Lucro real líquido; sem custos informados, o valor bruto da corrida. */
    val netProfit: BigDecimal
        get() = fare - (totalCost ?: BigDecimal.ZERO)
}
