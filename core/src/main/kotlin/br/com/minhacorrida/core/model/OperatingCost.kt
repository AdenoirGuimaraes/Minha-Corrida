package br.com.minhacorrida.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Custos do carro informados pelo motorista, usados no lucro real líquido.
 *
 * @property fuelPricePerLiter preço do litro do combustível (R$/l).
 * @property kmPerLiter consumo médio do carro (km/l).
 * @property otherCostPerKm outros custos por km (manutenção, pneus); zero se não informado.
 */
data class OperatingCost(
    val fuelPricePerLiter: BigDecimal,
    val kmPerLiter: BigDecimal,
    val otherCostPerKm: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(fuelPricePerLiter.signum() > 0) { "Preço do combustível deve ser positivo: $fuelPricePerLiter" }
        require(kmPerLiter.signum() > 0) { "Consumo deve ser positivo: $kmPerLiter" }
        require(otherCostPerKm.signum() >= 0) { "Outros custos não podem ser negativos: $otherCostPerKm" }
    }

    /** Custo por km rodado = combustível ÷ consumo + outros custos (4 casas, arredondado no fim). */
    val costPerKm: BigDecimal
        get() = fuelPricePerLiter.divide(kmPerLiter, 4, RoundingMode.HALF_UP) + otherCostPerKm
}
