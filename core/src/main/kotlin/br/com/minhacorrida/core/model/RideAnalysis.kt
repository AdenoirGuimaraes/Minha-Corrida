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

/** Agregado entregue à camada de apresentação. */
data class RideAnalysis(
    val offer: RideOffer,
    val metrics: RideMetrics,
    val rating: RideRating,
)
