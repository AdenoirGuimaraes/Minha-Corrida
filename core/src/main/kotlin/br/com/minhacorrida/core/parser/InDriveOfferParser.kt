package br.com.minhacorrida.core.parser

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.NoOpLogger
import java.math.BigDecimal

/**
 * Lê ofertas do inDrive (lista "Pedidos de viagem" e tela "Pedido de viagem").
 * Exemplo: "R$ 1/km ~7,3 km" / "R$ 10" / bolha do mapa "6 min 3,3 km" / "Aceitar por R$ 10".
 *
 * - Viagem: distância com "~".
 * - Busca: distância sem "~" diferente da viagem (só existe na tela de detalhe).
 */
class InDriveOfferParser(private val logger: AppLogger = NoOpLogger) : RideOfferParser {

    override val platform = Platform.INDRIVE

    override fun parse(texts: List<String>): RideOffer? {
        return try {
            val fare = findAcceptFare(texts) ?: TextPatterns.findFirstFare(texts) ?: return null
            val tripKm = findTripKm(texts) ?: return null
            RideOffer(platform, fare, tripKm = tripKm, pickupKm = findPickupKm(texts, tripKm))
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao ler oferta do inDrive", e)
            null
        }
    }

    /** "Aceitar por R$ 10" é o valor mais confiável na tela de detalhe. */
    private fun findAcceptFare(texts: List<String>): BigDecimal? =
        texts.firstNotNullOfOrNull { text ->
            ACCEPT_FARE.find(text)?.let { NumberParser.parse(it.groupValues[1]) }
        }

    private fun findTripKm(texts: List<String>): BigDecimal? =
        texts.firstNotNullOfOrNull { text ->
            TextPatterns.APPROX_DISTANCE.find(text)
                ?.let { TextPatterns.toKm(it.groupValues[1], it.groupValues[2]) }
        }

    // Ignora distâncias "~" e o rótulo do mapa igual ao trajeto (ex.: "7,3 km").
    private fun findPickupKm(texts: List<String>, tripKm: BigDecimal): BigDecimal? =
        texts.asSequence()
            .flatMap { text ->
                TextPatterns.DISTANCE.findAll(text)
                    .filterNot { TextPatterns.isApproximate(text, it) }
                    .mapNotNull { TextPatterns.toKm(it.groupValues[1], it.groupValues[2]) }
            }
            .firstOrNull { (it - tripKm).abs() > SAME_DISTANCE_TOLERANCE_KM }

    private companion object {
        const val TAG = "InDriveOfferParser"
        val SAME_DISTANCE_TOLERANCE_KM = BigDecimal("0.05")
        val ACCEPT_FARE = Regex(
            """(?:Aceitar\s+por|Accept\s+for)\s+R\$\s*(\d+(?:[.,]\d+)*)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
