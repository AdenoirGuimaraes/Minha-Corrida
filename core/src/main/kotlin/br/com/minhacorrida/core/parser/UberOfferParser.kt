package br.com.minhacorrida.core.parser

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.NoOpLogger

/**
 * Lê o cartão de oferta do Uber Driver. Exemplo de tela:
 * "R$ 8,91" / "12 min (6.6 km)" (até o passageiro) / "5 minutos (2.3 km)" (viagem).
 */
class UberOfferParser(private val logger: AppLogger = NoOpLogger) : RideOfferParser {

    override val platform = Platform.UBER

    override fun parse(texts: List<String>): RideOffer? {
        return try {
            val fare = TextPatterns.findFirstFare(texts) ?: return null

            // A Uber mostra as distâncias entre parênteses: 1ª = busca, 2ª = viagem.
            val distances = texts.flatMap { text ->
                TextPatterns.PAREN_DISTANCE.findAll(text)
                    .mapNotNull { TextPatterns.toKm(it.groupValues[1], it.groupValues[2]) }
                    .toList()
            }

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

    private companion object {
        const val TAG = "UberOfferParser"
    }
}
