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
 */
data class RideRules(
    val minValuePerKm: BigDecimal,
    val minPassengerRating: BigDecimal? = null,
    val operatingCost: OperatingCost? = null,
)

/**
 * Avalia uma oferta já lida: R$/km, verde/vermelho, valor mínimo e lucro real líquido.
 * Usada pelo leitor de tela e pelo simulador do app — a mesma regra nos dois lugares.
 */
class EvaluateRideUseCase(private val calculateMetrics: CalculateRideMetricsUseCase) {

    operator fun invoke(offer: RideOffer, rules: RideRules): RideAnalysis {
        val metrics = calculateMetrics(offer)
        val lowRating = isRatingBelowMinimum(offer.passengerRating, rules.minPassengerRating)
        // Verde só se atingir o R$/km mínimo E a nota do passageiro não estiver abaixo do mínimo.
        val rating = if (metrics.valuePerKm >= rules.minValuePerKm && !lowRating) {
            RideRating.GOOD
        } else {
            RideRating.BELOW_MINIMUM
        }
        return RideAnalysis(
            offer = offer,
            metrics = metrics,
            rating = rating,
            minimum = calculateMetrics.minimumFare(metrics, rules.minValuePerKm),
            profit = rules.operatingCost?.let { calculateMetrics.profit(offer, metrics, it) },
            lowPassengerRating = lowRating,
        )
    }

    /** Passageiro sem nota (novo) não é recusado: não há como avaliar. */
    private fun isRatingBelowMinimum(rating: BigDecimal?, min: BigDecimal?): Boolean =
        min != null && rating != null && rating < min
}
