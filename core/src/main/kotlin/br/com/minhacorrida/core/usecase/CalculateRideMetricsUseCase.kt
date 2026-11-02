package br.com.minhacorrida.core.usecase

import br.com.minhacorrida.core.model.MinimumFare
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.RideProfit
import br.com.minhacorrida.core.model.RideMetrics
import br.com.minhacorrida.core.model.RideOffer
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Regra central do Minha Corrida:
 * km total = busca + viagem; R$/km = valor da corrida ÷ km total.
 */
class CalculateRideMetricsUseCase {

    operator fun invoke(offer: RideOffer): RideMetrics {
        val totalKm = (offer.tripKm + (offer.pickupKm ?: BigDecimal.ZERO))
            .setScale(2, RoundingMode.HALF_UP)
        val valuePerKm = offer.fare.divide(totalKm, 2, RoundingMode.HALF_UP)
        return RideMetrics(
            totalKm = totalKm,
            valuePerKm = valuePerKm,
            includesPickup = offer.pickupKm != null,
        )
    }

    /**
     * Valor da corrida que atinge o mínimo: mínimo R$/km × km total,
     * arredondado PARA CIMA no centavo (para nunca ficar abaixo do mínimo).
     */
    /**
     * Lucro real líquido: valor da corrida − (km total × custo por km).
     * O km total inclui o deslocamento até o passageiro, que também gasta combustível.
     */
    fun profit(offer: RideOffer, metrics: RideMetrics, cost: OperatingCost): RideProfit {
        val costPerKm = cost.costPerKm
        val rideCost = (metrics.totalKm * costPerKm).setScale(2, RoundingMode.HALF_UP)
        return RideProfit(
            costPerKm = costPerKm.setScale(2, RoundingMode.HALF_UP),
            cost = rideCost,
            netProfit = offer.fare.setScale(2, RoundingMode.HALF_UP) - rideCost,
        )
    }

    fun minimumFare(metrics: RideMetrics, minValuePerKm: BigDecimal): MinimumFare =
        MinimumFare(
            minValuePerKm = minValuePerKm,
            minimumFare = (minValuePerKm * metrics.totalKm).setScale(2, RoundingMode.CEILING),
        )
}
