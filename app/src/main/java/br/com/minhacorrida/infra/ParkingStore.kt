package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.km.GeoPoint

/**
 * Onde o carro ficou estacionado: último ponto do GPS do trajeto que terminou. É de onde sai o
 * próximo trajeto ([br.com.minhacorrida.core.km.TripStartEstimator]). Se o motorista DESCARTA o
 * trajeto (ex.: carro do trabalho), volta o estacionamento anterior — o carro dele não saiu de lá.
 */
class ParkingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(): GeoPoint? = read(PREFIX_CURRENT)

    /** Fim de trajeto: guarda o novo estacionamento e o anterior (para desfazer no Descartar). */
    @Synchronized
    fun update(point: GeoPoint, tripId: String) {
        val editor = prefs.edit()
        current()?.let { write(editor, PREFIX_PREVIOUS, it) }
        write(editor, PREFIX_CURRENT, point)
        editor.putString(KEY_TRIP, tripId).apply()
    }

    /** Trajeto descartado: se o estacionamento atual veio dele, volta o anterior. */
    @Synchronized
    fun revertIfFrom(tripId: String) {
        if (prefs.getString(KEY_TRIP, null) != tripId) return
        val previous = read(PREFIX_PREVIOUS)
        val editor = prefs.edit().remove(KEY_TRIP)
        if (previous == null) {
            listOf("latBits", "lonBits", "acc", "t").forEach { editor.remove(PREFIX_CURRENT + it) }
        } else {
            write(editor, PREFIX_CURRENT, previous)
        }
        editor.apply()
    }

    private fun read(prefix: String): GeoPoint? {
        if (!prefs.contains(prefix + "latBits")) return null
        return GeoPoint(
            java.lang.Double.longBitsToDouble(prefs.getLong(prefix + "latBits", 0L)),
            java.lang.Double.longBitsToDouble(prefs.getLong(prefix + "lonBits", 0L)),
            prefs.getFloat(prefix + "acc", 0f),
            prefs.getLong(prefix + "t", 0L),
        )
    }

    private fun write(editor: android.content.SharedPreferences.Editor, prefix: String, p: GeoPoint) {
        // Latitude/longitude em bits de Double: Float perderia ~1 m de precisão.
        editor.putLong(prefix + "latBits", p.latitude.toRawBits())
            .putLong(prefix + "lonBits", p.longitude.toRawBits())
            .putFloat(prefix + "acc", p.accuracyMeters)
            .putLong(prefix + "t", p.timeMillis)
    }

    private companion object {
        const val PREFS_NAME = "minha_corrida_parking"
        const val PREFIX_CURRENT = "cur_"
        const val PREFIX_PREVIOUS = "prev_"
        const val KEY_TRIP = "trip_id"
    }
}
