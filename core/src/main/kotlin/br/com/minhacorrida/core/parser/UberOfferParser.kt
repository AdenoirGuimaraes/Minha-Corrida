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

    /**
     * Lê o cartão ancorado nas distâncias "(x km)": o valor da corrida é o ÚLTIMO "R$" antes
     * delas e a nota fica entre o valor e as distâncias. Assim, textos de outro app que
     * aparecem acima do cartão no OCR (ex.: lista do inDrive com "R$ 10" e "5.0(24)",
     * prints de 03/11) não são confundidos com a oferta da Uber.
     */
    override fun parse(texts: List<String>): RideOffer? {
        return try {
            val reading = readCard(texts) ?: return null
            // O cartão sempre traz as duas distâncias (busca e viagem). Com só uma,
            // a parte de baixo ainda não foi lida: mostrar o R$/km agora daria um valor errado.
            if (reading.distances.size < 2) return null
            RideOffer(
                platform, reading.fare, tripKm = reading.distances[1].km, pickupKm = reading.distances[0].km,
                passengerRating = reading.rating,
                pickupMinutes = reading.distances[0].minutes,
                tripMinutes = reading.distances[1].minutes,
                destination = br.com.minhacorrida.core.destination.DestinationReader.uber(texts),
            )
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
        readCard(texts)?.distances?.size == 1
    } catch (e: Exception) {
        false
    }

    /** Valor, distâncias (só as que vêm depois do valor) e nota de um cartão. */
    private data class CardReading(val fare: BigDecimal, val distances: List<Leg>, val rating: BigDecimal?)

    /** Trecho do cartão: "12 min (6.6 km)" → 6,6 km em 12 min. */
    private data class Leg(val km: BigDecimal, val minutes: Int?)

    private fun readCard(texts: List<String>): CardReading? {
        val anchor = distanceAnchor(texts)
        val fareHit = pickFare(texts, anchor) ?: return null
        val fare = fareValue(fareHit.value) ?: return null
        val afterFare = texts.subList(fareHit.index, texts.size)
        val ratingEnd = (anchor ?: texts.size).coerceAtLeast(fareHit.index)
        return CardReading(
            fare = fare,
            distances = findDistances(afterFare),
            rating = TextPatterns.findPassengerRating(texts.subList(fareHit.index, ratingEnd)),
        )
    }

    /** Índice do primeiro texto com distância do cartão: "(6.6 km)"; sem parênteses, "6.6 km". */
    private fun distanceAnchor(texts: List<String>): Int? {
        texts.indexOfFirst { TextPatterns.PAREN_DISTANCE.containsMatchIn(it) }.takeIf { it >= 0 }?.let { return it }
        return texts.indexOfFirst { text ->
            TextPatterns.DISTANCE.findAll(text).any { isCardPlainDistance(text, it) }
        }.takeIf { it >= 0 }
    }

    /**
     * Distância sem parênteses só vale se vier depois dos minutos no MESMO texto
     * ("12 min 6.6 km", OCR que perdeu os parênteses). Textos soltos como "50 m" ou
     * "2,5 km" são da navegação durante a corrida (log de 03/11, que gerou o card em loop).
     */
    private fun isCardPlainDistance(text: String, match: MatchResult): Boolean =
        !TextPatterns.isApproximate(text, match) &&
            MINUTES.containsMatchIn(text.substring(0, match.range.first))

    /** Último valor antes das distâncias; sem âncora (ou nada antes dela), o primeiro valor da tela. */
    private fun pickFare(texts: List<String>, anchor: Int?): IndexedValue<String>? {
        val fares = TextPatterns.fareTexts(texts)
        if (anchor == null) return fares.firstOrNull()
        return fares.lastOrNull { it.index <= anchor } ?: fares.firstOrNull()
    }

    /**
     * A Uber sempre mostra centavos ("R$ 7,04"). Se o OCR perder a vírgula ("R$ 704",
     * log de 02/11 que deu R$ 100,57/km), os dois últimos dígitos são os centavos.
     * Valores curtos sem vírgula ("R$ 15") ficam como estão.
     */
    private fun fareValue(raw: String): BigDecimal? {
        val missingComma = raw.none { it == ',' || it == '.' } && raw.length >= MIN_DIGITS_WITHOUT_COMMA
        return if (missingComma) {
            logger.warn(TAG, "Valor \"R$ $raw\" sem vírgula no OCR; lido como centavos")
            BigDecimal(raw).movePointLeft(2)
        } else {
            NumberParser.parse(raw)
        }
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
    private fun findDistances(texts: List<String>): List<Leg> {
        val inParentheses = texts.flatMap { text ->
            TextPatterns.PAREN_DISTANCE.findAll(text).mapNotNull { leg(text, it) }.toList()
        }
        if (inParentheses.size >= 2) return inParentheses

        val plain = texts.flatMap { text ->
            TextPatterns.DISTANCE.findAll(text)
                .filter { isCardPlainDistance(text, it) }
                .mapNotNull { leg(text, it) }
                .toList()
        }
        return if (plain.size > inParentheses.size) plain else inParentheses
    }

    private fun leg(text: String, match: MatchResult): Leg? =
        distanceKm(text, match)?.let { Leg(it, TextPatterns.minutesBefore(text, match)) }

    /**
     * Converte a distância e corrige o ponto perdido pelo OCR: "12 min (74 km)" seria
     * 370 km/h — impossível —, então é 7,4 km (print de 03/11). Usa os minutos do
     * mesmo texto; sem minutos, ou com número que já tem separador, fica como está.
     */
    private fun distanceKm(text: String, match: MatchResult): BigDecimal? {
        val number = match.groupValues[1]
        val km = TextPatterns.toKm(number, match.groupValues[2]) ?: return null
        val inKm = match.groupValues[2].equals("km", ignoreCase = true)
        if (!inKm || number.any { it == ',' || it == '.' }) return km
        val minutes = MINUTES.findAll(text.substring(0, match.range.first)).lastOrNull()
            ?.groupValues?.get(1)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return km
        val kmPerMinute = km.divide(minutes, 3, java.math.RoundingMode.HALF_UP)
        if (kmPerMinute <= MAX_KM_PER_MINUTE) return km
        val fixed = km.movePointLeft(1)
        logger.warn(TAG, "Distância \"$number km\" em $minutes min é impossível; lida como $fixed km")
        return fixed
    }

    private companion object {
        const val TAG = "UberOfferParser"
        const val MIN_DIGITS_WITHOUT_COMMA = 3

        /** 1,5 km/min = 90 km/h: acima disso, distância urbana lida pelo OCR perdeu o ponto. */
        val MAX_KM_PER_MINUTE = BigDecimal("1.5")
        val MINUTES = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    }
}
