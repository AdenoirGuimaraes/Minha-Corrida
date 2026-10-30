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

            // O cartão sempre traz as duas distâncias (busca e viagem). Com só uma,
            // a parte de baixo ainda não foi lida: mostrar o R$/km agora daria um valor errado.
            if (distances.size < 2) return null
            RideOffer(platform, fare, tripKm = distances[1], pickupKm = distances[0])
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao ler oferta da Uber", e)
            null
        }
    }

    /**
     * True quando o cartão foi lido pela metade: há valor e uma única distância
     * (parte de cima lida, parte de baixo não). O serviço insiste em vez de desistir.
     */
    override fun isPartialOffer(texts: List<String>): Boolean = try {
        TextPatterns.findFirstFare(texts) != null && findDistances(texts).size == 1
    } catch (e: Exception) {
        false
    }

    /**
     * Se a acessibilidade não forma uma oferta completa, o cartão só pode ser lido por OCR.
     * (Não basta ter um "R$" na tela: a tela inicial mostra os ganhos do dia, ex. "R$ 13,92".)
     */
    override fun visualReadingMode(texts: List<String>): VisualReadingMode =
        if (parse(texts) == null) {
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
