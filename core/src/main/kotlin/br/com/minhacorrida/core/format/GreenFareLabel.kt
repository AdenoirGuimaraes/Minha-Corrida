package br.com.minhacorrida.core.format

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Texto de baixo do quadrado "Mín. verde" (pedido de 15/11): diz QUAL mínimo decidiu o valor
 * e o que a corrida daria se fosse paga nesse valor (o mostrado, já arredondado no inDrive):
 * - decidido pelo R$/km (corridas longas): "Pelo R$ 2,00/km\nLucro R$ 11,11\n≈ R$ 0,46/min";
 * - decidido pelo lucro (corridas curtas): "Pelo lucro R$ 6,00\nR$ 2,60/km\nR$ 0,40/min".
 * Sem lucro mínimo (ou sem custos), só "R$ 2,00/km" (+ R$/min se houver).
 */
object GreenFareLabel {

    /** Qual mínimo define o Mín. verde. */
    enum class Rule { KM, PROFIT }

    /**
     * @param greenFare Mín. verde exato.
     * @param cost gasto da corrida (gasolina + manutenção); null sem custos.
     * @param minNetProfit lucro mínimo (null = filtro desligado ou sem custos).
     * @param minutes minutos da corrida, para a linha R$/min; null = não mostra.
     */
    fun text(
        greenFare: BigDecimal,
        platform: Platform,
        feePercent: BigDecimal,
        totalKm: BigDecimal,
        cost: BigDecimal?,
        minValuePerKm: BigDecimal,
        minNetProfit: BigDecimal?,
        minutes: Int?,
        minutesEstimated: Boolean,
        /** Volta vazia já somada ao [greenFare] (bairro LONGE); sai do lucro mostrado. */
        returnCost: BigDecimal? = null,
    ): String {
        val shown = CounterOffer.displayGreenFare(greenFare, platform)
        val back = returnCost ?: BigDecimal.ZERO
        // Líquido no Mín. verde menos a volta: o que sobra de verdade pagando a volta.
        val fullNet = CalculateRideMetricsUseCase.netOf(shown, feePercent)
        val net = fullNet - back
        val lines = mutableListOf<String>()
        if (cost == null || minNetProfit == null) {
            lines += "R$ ${money(minValuePerKm)}/km"
        } else {
            when (rule(totalKm, feePercent, minValuePerKm, cost, minNetProfit)) {
                Rule.KM -> {
                    lines += "Pelo R$ ${money(minValuePerKm)}/km"
                    lines += "Lucro R$ ${money(net - cost)}"
                }
                Rule.PROFIT -> {
                    lines += "Pelo lucro R$ ${money(minNetProfit)}"
                    if (totalKm.signum() > 0) lines += "R$ ${money(fullNet.divide(totalKm, 2, RoundingMode.HALF_UP))}/km"
                }
            }
        }
        if (back.signum() > 0) lines.add(1.coerceAtMost(lines.size), "+ volta R$ ${money(back)}")
        if (cost != null && minutes != null && minutes > 0) {
            val perMinute = (net - cost).divide(BigDecimal(minutes), 2, RoundingMode.HALF_UP)
            lines += "${if (minutesEstimated) "≈ " else ""}R$ ${money(perMinute)}/min"
        }
        return lines.joinToString("\n")
    }

    /** O mínimo que pede o MAIOR valor de corrida é o que decide (empate: R$/km). */
    fun rule(totalKm: BigDecimal, feePercent: BigDecimal, minValuePerKm: BigDecimal, cost: BigDecimal, minNetProfit: BigDecimal): Rule {
        val byKm = CalculateRideMetricsUseCase.grossFor(minValuePerKm * totalKm, feePercent)
        val byProfit = CalculateRideMetricsUseCase.grossFor(cost + minNetProfit, feePercent)
        return if (byKm >= byProfit) Rule.KM else Rule.PROFIT
    }

    private fun money(v: BigDecimal) = v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
