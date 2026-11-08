package br.com.minhacorrida.core.model

import java.math.BigDecimal

/**
 * Resultado do cálculo: km total rodado e quanto se ganha por km.
 *
 * @property grossFare valor da corrida mostrado no app de corrida.
 * @property netFare o que fica com o motorista: valor − taxa da plataforma
 *   (o inDrive cobra a taxa depois; na Uber o valor do card já vem descontado, taxa 0).
 * @property feePercent taxa da plataforma aplicada (%).
 * @property valuePerKm R$/km calculado sobre o [netFare].
 */
data class RideMetrics(
    val totalKm: BigDecimal,
    val valuePerKm: BigDecimal,
    val includesPickup: Boolean,
    val grossFare: BigDecimal,
    val netFare: BigDecimal,
    val feePercent: BigDecimal,
) {
    /** Valor da taxa da plataforma nesta corrida (R$). */
    val platformFee: BigDecimal get() = grossFare - netFare
}

/**
 * Classificação da corrida (cor do card):
 * - [GOOD] verde: atinge o mínimo R$/km e o lucro líquido mínimo;
 * - [LOW_PROFIT] amarelo: atinge o mínimo R$/km, mas o lucro líquido fica abaixo do mínimo;
 * - [BELOW_MINIMUM] vermelho: abaixo do mínimo R$/km ou nota do passageiro abaixo da mínima.
 */
enum class RideRating {
    GOOD,
    LOW_PROFIT,
    BELOW_MINIMUM;

    /** Verde ou amarelo: vale guardar no histórico (o motorista pode aceitar). */
    val isAcceptable: Boolean get() = this != BELOW_MINIMUM
}

/**
 * Valores mínimos da corrida (o que o passageiro paga, no app).
 *
 * @property minValuePerKm mínimo configurado pelo motorista (R$/km).
 * @property minimumFare valor da corrida que atinge esse mínimo no km total.
 * @property minNetProfit lucro líquido mínimo usado no [greenFare]; null quando não entra
 *   (filtro desligado ou custos do carro não informados).
 * @property greenFare menor valor que deixa o card VERDE: atinge o mínimo R$/km E o lucro
 *   líquido mínimo (o maior dos dois valores). Sem lucro mínimo, é igual ao [minimumFare].
 */
data class MinimumFare(
    val minValuePerKm: BigDecimal,
    val minimumFare: BigDecimal,
    val minNetProfit: BigDecimal? = null,
    val greenFare: BigDecimal = minimumFare,
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
) {
    /**
     * Lucro se a corrida pagasse exatamente o valor mínimo (mínimo R$/km × km total) − gasto.
     * null quando os custos do carro não foram informados.
     */
    val profitAtMinimumFare: java.math.BigDecimal?
        get() = profit?.let {
            // O que ficaria com o motorista no valor mínimo (mínimo R$/km × km) − gasto.
            (minimum.minValuePerKm * metrics.totalKm).setScale(2, java.math.RoundingMode.CEILING) - it.cost
        }
}
