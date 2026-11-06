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

    /**
     * @param feePercent taxa que a plataforma desconta DEPOIS (inDrive ~10%); 0 quando o
     *   valor mostrado já é o ganho do motorista (Uber, corrida particular).
     */
    operator fun invoke(offer: RideOffer, feePercent: BigDecimal = BigDecimal.ZERO): RideMetrics {
        val totalKm = (offer.tripKm + (offer.pickupKm ?: BigDecimal.ZERO))
            .setScale(2, RoundingMode.HALF_UP)
        val gross = offer.fare.setScale(2, RoundingMode.HALF_UP)
        val net = netOf(gross, feePercent)
        return RideMetrics(
            totalKm = totalKm,
            valuePerKm = net.divide(totalKm, 2, RoundingMode.HALF_UP),
            includesPickup = offer.pickupKm != null,
            grossFare = gross,
            netFare = net,
            feePercent = feePercent,
        )
    }

    /**
     * Lucro real líquido: o que fica com o motorista − (km total × custo por km).
     * O km total inclui o deslocamento até o passageiro, que também gasta combustível.
     */
    fun profit(offer: RideOffer, metrics: RideMetrics, cost: OperatingCost): RideProfit {
        val costPerKm = cost.costPerKm
        val rideCost = (metrics.totalKm * costPerKm).setScale(2, RoundingMode.HALF_UP)
        return RideProfit(
            costPerKm = costPerKm.setScale(2, RoundingMode.HALF_UP),
            cost = rideCost,
            netProfit = metrics.netFare - rideCost,
        )
    }

    /**
     * Valor da corrida (o que o passageiro paga, no app) que atinge o mínimo R$/km depois
     * da taxa: mínimo × km ÷ (1 − taxa), arredondado PARA CIMA no centavo.
     * Ex.: inDrive 10%, 8,6 km, mínimo R$ 2,00 → R$ 19,12 (não R$ 17,20).
     */
    fun minimumFare(metrics: RideMetrics, minValuePerKm: BigDecimal): MinimumFare {
        val net = minValuePerKm * metrics.totalKm
        val keep = (HUNDRED - metrics.feePercent).divide(HUNDRED, 6, RoundingMode.HALF_UP)
        return MinimumFare(
            minValuePerKm = minValuePerKm,
            minimumFare = net.divide(keep, 2, RoundingMode.CEILING),
        )
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        /** Valor que fica com o motorista depois da taxa da plataforma (2 casas). */
        fun netOf(gross: BigDecimal, feePercent: BigDecimal): BigDecimal =
            if (feePercent.signum() == 0) {
                gross.setScale(2, RoundingMode.HALF_UP)
            } else {
                (gross - gross * feePercent / HUNDRED).setScale(2, RoundingMode.HALF_UP)
            }
    }
}
