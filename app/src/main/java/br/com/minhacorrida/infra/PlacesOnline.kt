package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.neighborhood.CityChoice
import br.com.minhacorrida.core.neighborhood.Neighborhood
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Listas da internet para a escolha da cidade e dos bairros:
 * - municípios do estado: API de localidades do IBGE (guardada no celular depois da 1ª vez);
 * - bairros da cidade: OpenStreetMap (Overpass), com o ponto central de cada bairro.
 * Tudo em segundo plano; falhas viram exceção com mensagem para o motorista.
 */
class PlacesOnline(context: Context, private val logger: AppLogger) {

    private val cacheDir = File(context.applicationContext.filesDir, "localidades").apply { mkdirs() }

    /** Município do IBGE (nome e código). */
    data class City(val name: String, val ibgeId: Long)

    class OnlineException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun cities(uf: String): List<City> {
        val cache = File(cacheDir, "municipios_$uf.json")
        val text = if (cache.exists() && cache.length() > 0) {
            cache.readText()
        } else {
            get("https://servicodados.ibge.gov.br/api/v1/localidades/estados/$uf/municipios").also { cache.writeText(it) }
        }
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { i -> array.getJSONObject(i).let { City(it.getString("nome"), it.getLong("id")) } }
                .sortedBy { it.name }
        } catch (e: Exception) {
            cache.delete()
            throw OnlineException("Lista de municípios ilegível. Tente de novo.", e)
        }
    }

    private val knownNames = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * Nomes (normalizados) dos municípios do estado já baixados — só o que está no celular, sem
     * internet; vazio se ainda não baixou. Usado para saber se um texto é mesmo outra cidade.
     */
    fun cachedCityNames(uf: String): Set<String> = knownNames.getOrPut(uf) {
        try {
            val cache = File(cacheDir, "municipios_$uf.json")
            if (!cache.exists()) return emptySet()
            val array = JSONArray(cache.readText())
            (0 until array.length()).map {
                br.com.minhacorrida.core.destination.DestinationAdvisor.normalize(array.getJSONObject(it).getString("nome"))
            }.toSet()
        } catch (e: Exception) {
            logger.warn(TAG, "Municípios guardados ilegíveis", e)
            emptySet()
        }
    }

    /** Bairros (e subdivisões) do OpenStreetMap dentro do município. */
    fun neighborhoods(city: CityChoice): List<Neighborhood> {
        val name = city.name.replace("\"", "")
        val query = """
            [out:json][timeout:40];
            area["ISO3166-2"="BR-${city.uf}"]->.uf;
            area["boundary"="administrative"]["admin_level"="8"]["name"="$name"](area.uf)->.city;
            (
              node["place"~"^(suburb|neighbourhood|quarter)$"](area.city);
              way["place"~"^(suburb|neighbourhood|quarter)$"](area.city);
              relation["place"~"^(suburb|neighbourhood|quarter)$"](area.city);
              relation["boundary"="administrative"]["admin_level"~"^(9|10)$"](area.city);
            );
            out tags center;
        """.trimIndent()
        val text = post("https://overpass-api.de/api/interpreter", "data=" + URLEncoder.encode(query, "UTF-8"))
        return try {
            val elements = JSONObject(text).getJSONArray("elements")
            (0 until elements.length()).mapNotNull { i ->
                val e = elements.getJSONObject(i)
                val bairro = e.optJSONObject("tags")?.optString("name").orEmpty().trim()
                if (bairro.isEmpty()) return@mapNotNull null
                val center = e.optJSONObject("center")
                val lat = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat")
                val lon = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon")
                Neighborhood(bairro, if (lat != null && lon != null) GeoPoint(lat, lon, 0f, 0) else null)
            }.distinctBy { it.name.lowercase() }
        } catch (e: Exception) {
            throw OnlineException("Resposta do mapa ilegível. Tente de novo mais tarde.", e)
        }
    }

    private fun get(url: String): String = request(url, null)
    private fun post(url: String, body: String): String = request(url, body)

    private fun request(url: String, body: String?): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "MinhaCorrida-Android")
                if (body != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw OnlineException("O servidor respondeu $code. Tente de novo mais tarde.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: OnlineException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Sem acesso a $url", e)
            throw OnlineException("Sem internet ou servidor fora do ar. Tente de novo.", e)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "PlacesOnline"
    }
}
