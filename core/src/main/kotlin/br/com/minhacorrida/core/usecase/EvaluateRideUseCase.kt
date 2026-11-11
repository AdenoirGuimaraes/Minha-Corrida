package br.com.minhacorrida.core.usecase

import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.model.RideRating
import java.math.BigDecimal

/**
 * Regras do motorista usadas para avaliar uma corrida.
 *
 * @property minValuePerKm mínimo aceitável (R$/km).
 * @property minPassengerRating nota mínima do passageiro; null = sem filtro.
 * @property operatingCost custos do carro; null = sem cálculo de lucro.
 * @property inDriveFeePercent taxa que o inDrive desconta depois de cada corrida (%).
 * @property minNetProfit lucro líquido mínimo por corrida (R$); null = sem esse filtro.
 */
data class RideRules(
    val minValuePerKm: BigDecimal,
    val minPassengerRating: BigDecimal? = null,
    val operatingCost: OperatingCost? = null,
    val inDriveFeePercent: BigDecimal = BigDecimal.ZERO,
    val minNetProfit: BigDecimal? = null,
    /** Lucro mínimo por minuto de corrida (busca + viagem); abaixo dele o card fica amarelo. */
    val minProfitPerMinute: BigDecimal? = null,
) {
    companion object {
        /** Regras de agora, das configurações do motorista. */
        fun from(settings: br.com.minhacorrida.core.port.SettingsRepository) = RideRules(
            minValuePerKm = settings.getMinValuePerKm(),
            minPassengerRating = settings.getMinPassengerRating(),
            operatingCost = settings.getOperatingCost(),
            inDriveFeePercent = settings.getInDriveFeePercent(),
            minNetProfit = settings.getMinNetProfit(),
            minProfitPerMinute = settings.getMinProfitPerMinute(),
        )
    }

    /** Taxa descontada depois da corrida: só no inDrive (Uber já mostra o ganho do motorista). */
    fun feeFor(platform: br.com.minhacorrida.core.model.Platform): BigDecimal =
        if (platform == br.com.minhacorrida.core.model.Platform.INDRIVE) inDriveFeePercent else BigDecimal.ZERO
}

/**
 * Avalia uma oferta já lida: R$/km, verde/vermelho, valor mínimo e lucro real líquido.
 * Usada pelo leitor de tela e pelo simulador do app — a mesma regra nos dois lugares.
 */
class EvaluateRideUseCase(private val calculateMetrics: CalculateRideMetricsUseCase) {

    operator fun invoke(offer: RideOffer, rules: RideRules): RideAnalysis {
        val metrics = calculateMetrics(offer, rules.feeFor(offer.platform))
        val lowRating = isRatingBelowMinimum(offer.passengerRating, rules.minPassengerRating)
        val profit = rules.operatingCost?.let { calculateMetrics.profit(offer, metrics, it) }
        val time = br.com.minhacorrida.core.model.RideTime.of(offer)
        val perMinute = profit?.netProfit?.divide(BigDecimal(time.minutes), 2, java.math.RoundingMode.HALF_UP)
        val rating = classify(metrics.valuePerKm, lowRating, profit?.netProfit, perMinute, rules)
        return RideAnalysis(
            offer = offer,
            metrics = metrics,
            rating = rating,
            minimum = calculateMetrics.minimumFare(
                metrics, rules.minValuePerKm, profit, rules.minNetProfit, time.minutes, rules.minProfitPerMinute,
            ),
            profit = profit,
            lowPassengerRating = lowRating,
            time = time,
        )
    }

    /**
     * Vermelho: abaixo do mínimo R$/km ou nota ruim. Amarelo: R$/km ok, mas lucro líquido
     * abaixo do mínimo. Verde: os dois ok. Sem lucro mínimo (ou sem custos informados,
     * quando não há como calcular o lucro), vale só o R$/km.
     */
    private fun classify(
        valuePerKm: BigDecimal,
        lowRating: Boolean,
        netProfit: BigDecimal?,
        profitPerMinute: BigDecimal?,
        rules: RideRules,
    ): RideRating =
        when {
            valuePerKm < rules.minValuePerKm || lowRating -> RideRating.BELOW_MINIMUM
            rules.minNetProfit != null && netProfit != null && netProfit < rules.minNetProfit -> RideRating.LOW_PROFIT
            // Corrida que prende muito tempo (trânsito, busca longa) para o que paga.
            rules.minProfitPerMinute != null && profitPerMinute != null && profitPerMinute < rules.minProfitPerMinute ->
                RideRating.LOW_PROFIT
            else -> RideRating.GOOD
        }

    /** Passageiro sem nota (novo) não é recusado: não há como avaliar. */
    private fun isRatingBelowMinimum(rating: BigDecimal?, min: BigDecimal?): Boolean =
        min != null && rating != null && rating < min
}
