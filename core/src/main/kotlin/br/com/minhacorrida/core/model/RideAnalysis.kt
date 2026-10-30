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

/** Agregado entregue à camada de apresentação. */
data class RideAnalysis(
    val offer: RideOffer,
    val metrics: RideMetrics,
    val rating: RideRating,
    val minimum: MinimumFare,
)
