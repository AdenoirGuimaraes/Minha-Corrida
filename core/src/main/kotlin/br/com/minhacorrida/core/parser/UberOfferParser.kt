package br.com.minhacorrida.core.parser

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.NoOpLogger
import java.math.BigDecimal

/**
 * Lê o cartão de oferta do Uber Driver. Exemplo de tela:
 * "R$ 8,91" / "12 min (6.6 km)" (até o passageiro) / "5 minutos (2.3 km)" (viagem).
 *
 * A Uber bloqueia a acessibilidade do cartão (diagnóstico de 30/10: 0 textos),
 * então os textos chegam pelo OCR da metade de baixo da tela.
 */
class UberOfferParser(private val logger: AppLogger = NoOpLogger) : RideOfferParser {

    override val platform = Platform.UBER

    override fun parse(texts: List<String>): RideOffer? {
        return try {
            val fare = TextPatterns.findFirstFare(texts) ?: return null
            val distances = findDistances(texts)

            when {
                distances.isEmpty() -> null
                distances.size == 1 -> RideOffer(platform, fare, tripKm = distances[0], pickupKm = null)
                else -> RideOffer(platform, fare, tripKm = distances[1], pickupKm = distances[0])
            }
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao ler oferta da Uber", e)
            null
        }
    }

    /** Sem valor legível pela acessibilidade, o cartão só pode ser lido por OCR. */
    override fun visualReadingMode(texts: List<String>): VisualReadingMode =
        if (TextPatterns.findFirstFare(texts) == null) {
            VisualReadingMode.LOWER_SCREEN_CONTINUOUS
        } else {
            VisualReadingMode.NONE
        }

    /**
     * A Uber mostra as distâncias entre parênteses: 1ª = busca, 2ª = viagem.
     * Se o OCR perder os parênteses, usa as distâncias simples na mesma ordem.
     */
    private fun findDistances(texts: List<String>): List<BigDecimal> {
        val inParentheses = texts.flatMap { text ->
            TextPatterns.PAREN_DISTANCE.findAll(text)
                .mapNotNull { TextPatterns.toKm(it.groupValues[1], it.groupValues[2]) }
                .toList()
        }
        if (inParentheses.size >= 2) return inParentheses

        val plain = texts.flatMap { text ->
            TextPatterns.DISTANCE.findAll(text)
                .filterNot { TextPatterns.isApproximate(text, it) }
                .mapNotNull { TextPatterns.toKm(it.groupValues[1], it.groupValues[2]) }
                .toList()
        }
        return if (plain.size > inParentheses.size) plain else inParentheses
    }

    private companion object {
        const val TAG = "UberOfferParser"
    }
}
