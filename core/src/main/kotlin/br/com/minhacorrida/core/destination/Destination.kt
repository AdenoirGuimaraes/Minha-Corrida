package br.com.minhacorrida.core.destination

import br.com.minhacorrida.core.km.DistanceAccumulator
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.model.Place
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

/**
 * Lê o destino (bairro e cidade) dos textos da oferta.
 * - Uber: endereço logo depois da 2ª distância ("6 minutos (2.3 km)"), às vezes em 2 linhas:
 *   "Rua Juvelina Ribeiro Antunes, 146," + "Caravágio, Lages" → bairro Caravágio, cidade Lages.
 * - inDrive: 2ª linha de endereço com parênteses: "Havan Lages 2 (Guarujá, Lages - SC)".
 */
object DestinationReader {
    private val DISTANCE_LINE = Regex("""\(\s*\d+(?:[.,]\d+)?\s*km\s*\)""", RegexOption.IGNORE_CASE)
    private val STOP_WORDS = setOf("aceitar", "accept", "confirmar", "recusar")
    private val PAREN_ADDRESS = Regex("""\(([^()]*\p{L}[^()]*)\)?\s*$""")
    private const val MAX_ADDRESS_LINES = 2

    fun uber(texts: List<String>): Place? = try {
        val last = texts.indexOfLast { DISTANCE_LINE.containsMatchIn(it) }
        if (last < 0) {
            null
        } else {
            val lines = texts.drop(last + 1)
                .takeWhile { line ->
                    val t = line.trim()
                    t.isNotEmpty() && t.lowercase() !in STOP_WORDS && !t.startsWith("R$") && !DISTANCE_LINE.containsMatchIn(t)
                }
                .take(MAX_ADDRESS_LINES)
            lines.takeIf { it.isNotEmpty() }?.let { commaAddress(it.joinToString(" ").replace(Regex("\\s+"), " ").trim()) }
        }
    } catch (e: Exception) {
        null
    }

    fun inDrive(texts: List<String>): Place? = try {
        val addresses = texts.filter { PAREN_ADDRESS.containsMatchIn(it) && it.contains('(') }
        addresses.getOrNull(1)?.let { line ->
            val inside = PAREN_ADDRESS.find(line)!!.groupValues[1]
            val parts = segments(inside)
            when {
                parts.isEmpty() -> null
                parts.size == 1 -> Place(line.trim(), afterDash(parts[0]), null)
                else -> Place(line.trim(), afterDash(parts[parts.size - 2]), city(parts.last()))
            }
        }
    } catch (e: Exception) {
        null
    }

    /** "Rua 31 de Março, 953, Guarujá, Lages" → bairro Guarujá, cidade Lages. */
    fun commaAddress(raw: String): Place? {
        val parts = segments(raw)
        return when {
            parts.isEmpty() -> null
            parts.size == 1 -> Place(raw, parts[0], null)
            else -> {
                val neighborhood = parts[parts.size - 2].takeUnless { it.any(Char::isDigit) && it.none(Char::isLetter) }
                Place(raw, neighborhood, city(parts.last()))
            }
        }
    }

    private fun segments(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** "Lages - SC" → "Lages". */
    private fun city(text: String) = text.substringBefore(" - ").trim()

    /** "Avenida Marechal Floriano - Centro" → "Centro". */
    private fun afterDash(text: String) = text.substringAfterLast(" - ").trim()
}

/**
 * Configurações do aviso de destino (Configurações → Destino da corrida).
 * @property base ponto de onde o motorista costuma trabalhar (para a volta vazia).
 * @property farKm acima disso (volta até a base), o destino é "longe".
 * @property avoid bairros que o motorista quer evitar (aviso ⛔).
 */
data class DestinationSettings(
    val enabled: Boolean = true,
    val base: GeoPoint = LAGES_CENTER,
    val baseIsDefault: Boolean = true,
    val baseCity: String = "Lages",
    val farKm: BigDecimal = BigDecimal("5.0"),
    val avoid: List<String> = emptyList(),
) {
    companion object {
        /** Centro de Lages (Praça João Costa), usado enquanto o motorista não marca a base dele. */
        val LAGES_CENTER = GeoPoint(-27.8157, -50.3264, 0f, 0)
    }
}

interface DestinationSettingsRepository {
    fun get(): DestinationSettings
    fun save(settings: DestinationSettings)
}

/** Texto e se é aviso (⚠️/⛔) para a linha do destino no card. */
data class DestinationHint(val text: String, val warning: Boolean)

/**
 * Monta a linha do destino no card (itens 1 e 2 do backlog):
 * "⚠️ Destino longe: Guarujá\nVolta vazia ≈ 8,4 km · R$ 7,14 → lucro c/ volta R$ 0,77".
 * A cor do card NÃO muda: é só um aviso a mais.
 */
object DestinationAdvisor {
    /** Volta menor que isso não vale a linha (destino perto da base). */
    val MIN_RETURN_KM: BigDecimal = BigDecimal("2.0")

    /** Ruas não são linha reta: mesmo fator do início estimado do trajeto. */
    const val ROAD_FACTOR = 1.3

    /** Km de volta (destino → base) estimados pela linha reta × [ROAD_FACTOR]. */
    fun returnKm(destination: GeoPoint, base: GeoPoint): BigDecimal =
        BigDecimal(DistanceAccumulator.haversineMeters(destination, base) * ROAD_FACTOR / 1000).setScale(1, RoundingMode.HALF_UP)

    /**
     * @param returnKm km de volta até a base; null se o endereço não foi localizado (ainda).
     * @param costPerKm gasto por km (gasolina + manutenção); null sem custos.
     * @param netProfit lucro da corrida; null sem custos.
     */
    fun advise(
        place: Place?,
        returnKm: BigDecimal?,
        costPerKm: BigDecimal?,
        netProfit: BigDecimal?,
        settings: DestinationSettings,
    ): DestinationHint? {
        if (!settings.enabled || place == null) return null
        val avoid = place.neighborhood?.let { n -> settings.avoid.any { same(it, n) } } ?: false
        val outside = place.city != null && !same(place.city, settings.baseCity)
        val far = returnKm != null && returnKm > settings.farKm
        val label = listOfNotNull(place.neighborhood, place.city?.takeIf { outside }).joinToString(", ")
            .ifEmpty { place.raw.take(40) }
        val headline = when {
            avoid -> "⛔ Bairro a evitar: $label"
            outside -> "⚠️ Fora de ${settings.baseCity}: $label"
            far -> "⚠️ Destino longe: $label"
            else -> "📍 Destino: $label"
        }
        val back = returnKm?.takeIf { it >= MIN_RETURN_KM && costPerKm != null }?.let { km ->
            val cost = (km * costPerKm!!).setScale(2, RoundingMode.HALF_UP)
            val after = netProfit?.let { it - cost }
            val result = when {
                after == null -> ""
                after.signum() < 0 -> " → prejuízo c/ volta R$ ${money(after.abs())}"
                else -> " → lucro c/ volta R$ ${money(after)}"
            }
            "Volta vazia ≈ ${km1(km)} km · R$ ${money(cost)}$result"
        }
        return DestinationHint(listOfNotNull(headline, back).joinToString("\n"), avoid || outside || far)
    }

    /** Compara nomes sem acento e sem maiúsculas ("guaruja" = "Guarujá"). */
    fun same(a: String, b: String) = normalize(a) == normalize(b)

    private fun normalize(text: String) =
        Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun money(v: BigDecimal) = v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
    private fun km1(v: BigDecimal) = v.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
