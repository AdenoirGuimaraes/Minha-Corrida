package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.destination.DestinationAdvisor
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.neighborhood.CityChoice
import br.com.minhacorrida.core.neighborhood.Neighborhood
import br.com.minhacorrida.core.neighborhood.NeighborhoodMark
import br.com.minhacorrida.core.neighborhood.NeighborhoodRepository
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cidade escolhida e bairros marcados (JSON nas preferências; entra no backup). A lista fica em
 * memória: o leitor consulta a cada oferta, e só as gravações tocam o disco.
 */
class SharedPrefsNeighborhoodRepository(context: Context, private val logger: AppLogger) : NeighborhoodRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    @Volatile private var cache: List<Neighborhood>? = null

    /** Avisado depois de cada mudança (ex.: refazer a regra do leitor, atualizar o aviso). */
    @Volatile var onChanged: () -> Unit = {}

    override fun city(): CityChoice? = try {
        val uf = prefs.getString(KEY_UF, null)
        val name = prefs.getString(KEY_CITY, null)
        if (uf == null || name == null) null else CityChoice(uf, name, prefs.getLong(KEY_IBGE, 0L).takeIf { it > 0 })
    } catch (e: Exception) {
        logger.error(TAG, "Cidade ilegível", e)
        null
    }

    @Synchronized
    override fun setCity(city: CityChoice) {
        val current = city()
        val same = current != null && current.uf == city.uf && DestinationAdvisor.same(current.name, city.name)
        prefs.edit().putString(KEY_UF, city.uf).putString(KEY_CITY, city.name).putLong(KEY_IBGE, city.ibgeId ?: 0L).apply()
        if (!same) write(emptyList()) else onChanged()
    }

    @Synchronized
    override fun all(): List<Neighborhood> = cache ?: read().also { cache = it }

    @Synchronized
    override fun mergeFromMap(found: List<Neighborhood>) {
        val list = all().toMutableList()
        found.forEach { f ->
            val index = list.indexOfFirst { DestinationAdvisor.same(it.name, f.name) }
            if (index >= 0) {
                if (list[index].point == null) list[index] = list[index].copy(point = f.point)
            } else {
                list += Neighborhood(f.name, f.point)
            }
        }
        write(list)
    }

    @Synchronized
    override fun setMark(name: String, mark: NeighborhoodMark?) {
        write(all().map { if (DestinationAdvisor.same(it.name, name)) it.copy(mark = mark, isNew = false) else it })
    }

    @Synchronized
    override fun remove(name: String) {
        write(all().filterNot { DestinationAdvisor.same(it.name, name) })
    }

    @Synchronized
    override fun add(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || all().any { DestinationAdvisor.same(it.name, clean) }) return
        write(all() + Neighborhood(clean))
    }

    @Synchronized
    override fun noteSeen(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || all().any { DestinationAdvisor.same(it.name, clean) }) return false
        write(all() + Neighborhood(clean, isNew = true))
        return true
    }

    private fun write(list: List<Neighborhood>) {
        try {
            val sorted = list.sortedBy { DestinationAdvisor.normalize(it.name) }
            val array = JSONArray()
            sorted.forEach { n ->
                array.put(
                    JSONObject().put("n", n.name).put("m", n.mark?.name ?: JSONObject.NULL).put("new", n.isNew)
                        .put("lat", n.point?.latitude ?: JSONObject.NULL).put("lng", n.point?.longitude ?: JSONObject.NULL),
                )
            }
            prefs.edit().putString(KEY_LIST, array.toString()).apply()
            cache = sorted
            onChanged()
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao gravar bairros", e)
        }
    }

    private fun read(): List<Neighborhood> = try {
        val array = JSONArray(prefs.getString(KEY_LIST, "[]"))
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val point = if (o.isNull("lat") || o.isNull("lng")) null else GeoPoint(o.getDouble("lat"), o.getDouble("lng"), 0f, 0)
                Neighborhood(
                    name = o.getString("n"),
                    point = point,
                    mark = if (o.isNull("m")) null else NeighborhoodMark.valueOf(o.getString("m")),
                    isNew = o.optBoolean("new", false),
                )
            }.getOrNull()
        }
    } catch (e: Exception) {
        logger.error(TAG, "Bairros ilegíveis", e)
        emptyList()
    }

    private companion object {
        const val TAG = "Neighborhoods"
        const val PREFS_NAME = "minha_corrida_neighborhoods"
        const val KEY_UF = "uf"
        const val KEY_CITY = "city"
        const val KEY_IBGE = "ibge"
        const val KEY_LIST = "list"
    }
}
