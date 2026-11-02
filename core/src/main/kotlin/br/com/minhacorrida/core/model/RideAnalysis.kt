package br.com.minhacorrida.core.model

import java.math.BigDecimal

/** Resultado do cálculo: km total rodado e quanto se ganha por km. */
data class RideMetrics(
    val totalKm: BigDecimal,
    val valuePerKm: BigDecimal,
    val includesPickup: Boolean,
)

/** Classificação da corrida frente ao mínimo configurado pelo motorista. */
enum class RideRating { GOOD, BELOW_MINIMUM }

/**
 * Valor mínimo da corrida para atingir o R$/km desejado.
 *
 * @property minValuePerKm mínimo configurado pelo motorista (R$/km).
 * @property minimumFare valor da corrida que atinge esse mínimo no km total.
 */
data class MinimumFare(
    val minValuePerKm: BigDecimal,
    val minimumFare: BigDecimal,
)

/**
 * Lucro real líquido da corrida.
 *
 * @property costPerKm custo do carro por km rodado (combustível + outros).
 * @property cost gasto da corrida inteira (km total × custo por km).
 * @property netProfit valor da corrida − gasto; negativo = prejuízo.
 */
data class RideProfit(
    val costPerKm: BigDecimal,
    val cost: BigDecimal,
    val netProfit: BigDecimal,
)

/**
 * Agregado entregue à camada de apresentação.
 *
 * @property profit null quando o motorista ainda não informou os custos do carro.
 * @property lowPassengerRating true quando a nota do passageiro está abaixo do mínimo.
 */
data class RideAnalysis(
    val offer: RideOffer,
    val metrics: RideMetrics,
    val rating: RideRating,
    val minimum: MinimumFare,
    val profit: RideProfit? = null,
    val lowPassengerRating: Boolean = false,
)
