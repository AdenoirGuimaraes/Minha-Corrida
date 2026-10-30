package br.com.minhacorrida.core.parser

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.NoOpLogger
import java.math.BigDecimal

/**
 * Lê a oferta do inDrive somente na tela aberta ("Pedido de viagem").
 *
 * As distâncias reais estão nas duas bolhas do mapa ("8 min 3,4 km" e "6 min 1,8 km"),
 * que são imagem — chegam aqui via OCR. O cartão traz "~1,8 km", que identifica
 * qual bolha é o trajeto; a outra é o deslocamento até o passageiro.
 * A lista principal é ignorada: sem a distância de busca o R$/km fica inflado.
 */
class InDriveOfferParser(private val logger: AppLogger = NoOpLogger) : RideOfferParser {

    override val platform = Platform.INDRIVE

    override fun parse(texts: List<String>): RideOffer? {
        return try {
            // "Aceitar por R$ X" só existe na oferta aberta: é o portão desta leitura.
            val fare = findAcceptFare(texts) ?: return null
            val distances = collectDistances(texts)
            val mapKms = distances.filterNot { it.approximate }.map { it.km }
            if (mapKms.size < 2) {
                logger.debug(TAG, "Oferta aberta, mas o mapa ainda não foi lido (${mapKms.size} distância(s))")
                return null
            }

            // Trajeto = bolha igual ao "~X km" do cartão; sem ele, a primeira bolha.
            val approxTrip = distances.firstOrNull { it.approximate }?.km
            val tripIndex = approxTrip
                ?.let { trip -> mapKms.indexOfFirst { isSame(it, trip) } }
                ?.takeIf { it >= 0 } ?: 0
            val tripKm = mapKms[tripIndex]
            val others = mapKms.filterIndexed { index, _ -> index != tripIndex }
            // Evita contar a mesma bolha duas vezes (lida pela acessibilidade e pelo OCR).
            val pickupKm = others.firstOrNull { !isSame(it, tripKm) } ?: others.first()

            RideOffer(platform, fare, tripKm = tripKm, pickupKm = pickupKm)
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao ler oferta do inDrive", e)
            null
        }
    }

    override fun visualReadingMode(texts: List<String>): VisualReadingMode =
        if (findAcceptFare(texts) != null && collectDistances(texts).count { !it.approximate } < 2) {
            VisualReadingMode.MAP_ONCE_PER_OFFER
        } else {
            VisualReadingMode.NONE
        }

    private fun findAcceptFare(texts: List<String>): BigDecimal? =
        texts.firstNotNullOfOrNull { text ->
            ACCEPT_FARE.find(text)?.let { NumberParser.parse(it.groupValues[1]) }
        }?.takeIf { it.signum() > 0 }

    private fun collectDistances(texts: List<String>): List<FoundDistance> =
        texts.flatMap { text ->
            TextPatterns.DISTANCE.findAll(text).mapNotNull { match ->
                TextPatterns.toKm(match.groupValues[1], match.groupValues[2])
                    ?.let { FoundDistance(it, TextPatterns.isApproximate(text, match)) }
            }.toList()
        }

    private fun isSame(a: BigDecimal, b: BigDecimal) = (a - b).abs() <= SAME_DISTANCE_TOLERANCE_KM

    private data class FoundDistance(val km: BigDecimal, val approximate: Boolean)

    private companion object {
        const val TAG = "InDriveOfferParser"
        val SAME_DISTANCE_TOLERANCE_KM = BigDecimal("0.05")
        val ACCEPT_FARE = Regex(
            """(?:Aceitar\s+por|Accept\s+for)\s+R\$\s*(\d+(?:[.,]\d+)*)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
