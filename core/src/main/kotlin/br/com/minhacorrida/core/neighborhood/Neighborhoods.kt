package br.com.minhacorrida.core.neighborhood

import br.com.minhacorrida.core.destination.DestinationAdvisor
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.model.Place
import java.math.BigDecimal

/**
 * Marcação de um bairro pelo motorista (pedido de 17/11):
 * - [GOOD] bom: dentro da base, vai sempre;
 * - [FAR] longe: só se o valor pagar a volta vazia (o Mín. verde inclui a volta);
 * - [AVOID] não vou: o card fica vermelho e pede para recusar.
 * Sem marcação = neutro (o card só mostra o nome).
 */
enum class NeighborhoodMark { GOOD, FAR, AVOID }

/**
 * Bairro da cidade escolhida.
 * @property point centro do bairro (do mapa); null para bairro só lido nas ofertas.
 * @property isNew apareceu numa oferta e ainda não foi marcado (aviso nas Configurações).
 */
data class Neighborhood(
    val name: String,
    val point: GeoPoint? = null,
    val mark: NeighborhoodMark? = null,
    val isNew: Boolean = false,
)

/** Cidade escolhida nas Configurações (estado + município do IBGE). */
data class CityChoice(val uf: String, val name: String, val ibgeId: Long? = null)

interface NeighborhoodRepository {
    fun city(): CityChoice?

    /** Troca a cidade: a lista de bairros recomeça vazia. */
    fun setCity(city: CityChoice)

    /** Bairros da cidade, em ordem alfabética. */
    fun all(): List<Neighborhood>

    /** Junta bairros vindos do mapa: novos entram sem marcação; os que já existem ganham o ponto. */
    fun mergeFromMap(found: List<Neighborhood>)

    /** Marca (ou desmarca, com null) um bairro; marcar tira o aviso de "novo". */
    fun setMark(name: String, mark: NeighborhoodMark?)

    /** Tira o bairro da lista (nome de rua ou lixo que entrou como "bairro novo"). */
    fun remove(name: String)

    /** Bairro adicionado à mão (não estava no mapa). */
    fun add(name: String)

    /**
     * Bairro lido numa oferta. Se não estava na lista, entra como novo (aviso para marcar).
     * @return true se entrou agora.
     */
    fun noteSeen(name: String): Boolean
}

/**
 * O destino de uma oferta, já com a marcação do motorista.
 * @property returnKm volta vazia até o bairro BOM mais perto (só para LONGE com ponto no mapa).
 */
data class DestinationInfo(
    val neighborhood: String,
    val mark: NeighborhoodMark?,
    val outsideCity: String? = null,
    val returnKm: BigDecimal? = null,
)

/** Classifica o destino de uma oferta. */
fun interface DestinationPolicy {
    fun classify(place: Place): DestinationInfo?
}

/**
 * Regra dos bairros: acha o bairro do destino na lista (sem acento/maiúsculas), aplica a
 * marcação e, para LONGE, calcula a volta até o bairro BOM mais perto (linha reta × 1,3).
 * Destino em outra cidade conta como LONGE (sem ponto, sem volta calculada).
 * Sem nenhum bairro BOM com ponto, a volta é até [fallbackBase].
 */
class NeighborhoodPolicy(
    private val city: CityChoice?,
    private val neighborhoods: List<Neighborhood>,
    private val fallbackBase: GeoPoint,
    /** Municípios do estado (IBGE, já normalizados); vazio = lista ainda não baixada. */
    private val knownCities: Set<String> = emptySet(),
) : DestinationPolicy {

    override fun classify(place: Place): DestinationInfo? {
        val name = place.neighborhood?.takeIf { it.isNotBlank() } ?: return null
        val outside = place.city?.takeIf { c -> city != null && isAnotherCity(c, city.name) }
        if (outside != null) return DestinationInfo(name, NeighborhoodMark.FAR, outsideCity = outside)
        val found = find(name)
        val mark = found?.mark
        val returnKm = if (mark == NeighborhoodMark.FAR) found.point?.let { returnKmFrom(it) } else null
        return DestinationInfo(found?.name ?: name, mark, returnKm = returnKm)
    }

    /**
     * "Outra cidade" só quando o texto é mesmo outro município (19/11: OCR "Laqes", "22° 16°" e
     * pedaços de endereço viravam outra cidade): parecido com a cidade escolhida (até 2 letras de
     * diferença) = mesma; com a lista do IBGE, só conta se for um município dela; sem a lista, só
     * texto limpo (letras e espaços).
     */
    fun isAnotherCity(text: String, chosen: String): Boolean {
        val t = DestinationAdvisor.normalize(text)
        val c = DestinationAdvisor.normalize(chosen)
        if (t.isEmpty() || t == c || (c.length >= 5 && editDistance(t, c) <= 2)) return false
        return if (knownCities.isNotEmpty()) t in knownCities else t.all { it.isLetter() || it == ' ' || it == '-' } && t.length >= 3
    }

    private fun editDistance(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val current = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
                previous = current
            }
        }
        return row[b.length]
    }

    fun find(name: String): Neighborhood? = neighborhoods.firstOrNull { DestinationAdvisor.same(it.name, name) }

    /** Do destino até o bairro BOM mais perto (ou a base, sem nenhum BOM no mapa). */
    fun returnKmFrom(destination: GeoPoint): BigDecimal {
        val goods = neighborhoods.filter { it.mark == NeighborhoodMark.GOOD }.mapNotNull { it.point }
        return (goods.ifEmpty { listOf(fallbackBase) }).map { DestinationAdvisor.returnKm(destination, it) }.min()
    }
}

/** Os 27 estados (sigla, nome) para a escolha da cidade. */
object BrazilianStates {
    val ALL: List<Pair<String, String>> = listOf(
        "AC" to "Acre", "AL" to "Alagoas", "AP" to "Amapá", "AM" to "Amazonas", "BA" to "Bahia",
        "CE" to "Ceará", "DF" to "Distrito Federal", "ES" to "Espírito Santo", "GO" to "Goiás",
        "MA" to "Maranhão", "MT" to "Mato Grosso", "MS" to "Mato Grosso do Sul", "MG" to "Minas Gerais",
        "PA" to "Pará", "PB" to "Paraíba", "PR" to "Paraná", "PE" to "Pernambuco", "PI" to "Piauí",
        "RJ" to "Rio de Janeiro", "RN" to "Rio Grande do Norte", "RS" to "Rio Grande do Sul",
        "RO" to "Rondônia", "RR" to "Roraima", "SC" to "Santa Catarina", "SP" to "São Paulo",
        "SE" to "Sergipe", "TO" to "Tocantins",
    )

    /** Municípios cujo nome começa pelas letras digitadas (sem acento/maiúsculas). */
    fun filterCities(all: List<String>, typed: String): List<String> {
        val t = DestinationAdvisor.normalize(typed)
        if (t.isEmpty()) return all
        return all.filter { DestinationAdvisor.normalize(it).startsWith(t) }
    }
}
