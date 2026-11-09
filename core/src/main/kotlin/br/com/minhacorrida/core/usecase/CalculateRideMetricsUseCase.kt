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
    fun minimumFare(metrics: RideMetrics, minValuePerKm: BigDecimal): MinimumFare =
        MinimumFare(
            minValuePerKm = minValuePerKm,
            minimumFare = grossFor(minValuePerKm * metrics.totalKm, metrics.feePercent),
        )

    /**
     * Mínimos para o card ficar VERDE: o valor do R$/km mínimo e o valor que deixa
     * [minNetProfit] de lucro (gasto + lucro mínimo, já com a taxa) — vale o MAIOR.
     * Ex.: Uber 7 km, R$ 1,00/km (R$ 7,00), gasto R$ 4,41, lucro mín. R$ 3,00 → R$ 7,41.
     *
     * @param profit lucro da corrida; null (sem custos) ou [minNetProfit] null = só o R$/km.
     */
    fun minimumFare(
        metrics: RideMetrics,
        minValuePerKm: BigDecimal,
        profit: RideProfit?,
        minNetProfit: BigDecimal?,
    ): MinimumFare {
        val byKm = minimumFare(metrics, minValuePerKm)
        if (profit == null || minNetProfit == null) return byKm
        val byProfit = grossFor(profit.cost + minNetProfit, metrics.feePercent)
        return byKm.copy(minNetProfit = minNetProfit, greenFare = byKm.minimumFare.max(byProfit))
    }

    private fun grossFor(net: BigDecimal, feePercent: BigDecimal): BigDecimal = Companion.grossFor(net, feePercent)

    companion object {
        private val HUNDRED = BigDecimal(100)

        /** Valor cobrado no app que deixa [net] com o motorista: net ÷ (1 − taxa), para cima. */
        fun grossFor(net: BigDecimal, feePercent: BigDecimal): BigDecimal {
            val keep = (HUNDRED - feePercent).divide(HUNDRED, 6, RoundingMode.HALF_UP)
            return net.divide(keep, 2, RoundingMode.CEILING)
        }

        /**
         * "Mín. verde" de uma distância já conhecida (corrida guardada): o maior entre o valor do
         * R$/km mínimo e o valor que deixa [minNetProfit] de lucro sobre [cost]; ambos com a taxa.
         * Sem [cost] ou sem [minNetProfit], só o R$/km.
         */
        fun greenFare(
            totalKm: BigDecimal,
            feePercent: BigDecimal,
            minValuePerKm: BigDecimal,
            cost: BigDecimal?,
            minNetProfit: BigDecimal?,
        ): BigDecimal {
            val byKm = grossFor(minValuePerKm * totalKm, feePercent)
            if (cost == null || minNetProfit == null) return byKm
            return byKm.max(grossFor(cost + minNetProfit, feePercent))
        }

        /** Valor que fica com o motorista depois da taxa da plataforma (2 casas). */
        fun netOf(gross: BigDecimal, feePercent: BigDecimal): BigDecimal =
            if (feePercent.signum() == 0) {
                gross.setScale(2, RoundingMode.HALF_UP)
            } else {
                (gross - gross * feePercent / HUNDRED).setScale(2, RoundingMode.HALF_UP)
            }
    }
}
