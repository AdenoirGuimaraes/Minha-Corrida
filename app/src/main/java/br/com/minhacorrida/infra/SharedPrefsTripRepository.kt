package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.km.Trip
import br.com.minhacorrida.core.km.TripRepository
import br.com.minhacorrida.core.km.TripStatus
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trajetos detectados (JSON numa chave das preferências). Guarda os últimos [MAX_TRIPS]:
 * os decididos só servem de histórico; os pendentes esperam o motorista.
 */
class SharedPrefsTripRepository(context: Context, private val logger: AppLogger) : TripRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun save(trip: Trip) {
        val trips = (all().filterNot { it.id == trip.id } + trip).sortedBy { it.startMillis }.takeLast(MAX_TRIPS)
        try {
            val array = JSONArray()
            trips.forEach { array.put(toJson(it)) }
            prefs.edit().putString(KEY, array.toString()).apply()
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao gravar trajeto", e)
        }
    }

    @Synchronized
    override fun find(id: String): Trip? = all().firstOrNull { it.id == id }

    @Synchronized
    override fun pending(): List<Trip> = all().filter { it.status == TripStatus.PENDING }.sortedBy { it.startMillis }

    @Synchronized
    override fun all(): List<Trip> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching { fromJson(array.getJSONObject(i)) }.getOrNull() }
    } catch (e: Exception) {
        logger.error(TAG, "Trajetos ilegíveis", e)
        emptyList()
    }

    private fun toJson(t: Trip) = JSONObject()
        .put("id", t.id).put("s", t.startMillis).put("e", t.endMillis)
        .put("m", t.meters).put("st", t.status.name).put("r", t.withRide).put("est", t.estimatedMeters)

    private fun fromJson(o: JSONObject) = Trip(
        id = o.getString("id"),
        startMillis = o.getLong("s"),
        endMillis = o.getLong("e"),
        meters = o.getDouble("m"),
        status = TripStatus.valueOf(o.getString("st")),
        withRide = o.optBoolean("r", false),
        estimatedMeters = o.optDouble("est", 0.0),
    )

    private companion object {
        const val TAG = "TripRepo"
        const val PREFS_NAME = "minha_corrida_trips"
        const val KEY = "trips"
        const val MAX_TRIPS = 300
    }
}
