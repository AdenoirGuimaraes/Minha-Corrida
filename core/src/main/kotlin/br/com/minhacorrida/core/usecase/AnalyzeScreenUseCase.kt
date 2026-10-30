package br.com.minhacorrida.core.usecase

import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.parser.ParserRegistry
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository

/**
 * Orquestra o fluxo: textos da tela → oferta → métricas → classificação.
 */
class AnalyzeScreenUseCase(
    private val registry: ParserRegistry,
    private val calculateMetrics: CalculateRideMetricsUseCase,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
) {

    /**
     * @param packageName pacote do app lido (ex.: "com.ubercab.driver").
     * @param texts textos visíveis na tela, em ordem de leitura.
     * @return análise da oferta, ou null se a tela não contiver uma oferta completa.
     */
    fun analyze(packageName: String, texts: List<String>): RideAnalysis? {
        return try {
            val parser = registry.parserFor(packageName) ?: return null
            val offer = parser.parse(texts) ?: return null
            val metrics = calculateMetrics(offer)
            val rating = if (metrics.valuePerKm >= settings.getMinValuePerKm()) {
                RideRating.GOOD
            } else {
                RideRating.BELOW_MINIMUM
            }
            RideAnalysis(offer, metrics, rating)
        } catch (e: Exception) {
            logger.error(TAG, "Erro ao analisar tela de $packageName", e)
            null
        }
    }

    /** True quando a oferta depende de dados que só existem como imagem na tela. */
    fun needsVisualReading(packageName: String, texts: List<String>): Boolean =
        try {
            registry.parserFor(packageName)?.needsVisualReading(texts) ?: false
        } catch (e: Exception) {
            logger.error(TAG, "Erro ao verificar leitura visual de $packageName", e)
            false
        }

    private companion object {
        const val TAG = "AnalyzeScreen"
    }
}
