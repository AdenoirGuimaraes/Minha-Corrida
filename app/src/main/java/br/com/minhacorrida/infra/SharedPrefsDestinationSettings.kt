package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.destination.DestinationSettings
import br.com.minhacorrida.core.destination.DestinationSettingsRepository
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.port.AppLogger
import java.math.BigDecimal

/** Configurações do destino nas preferências principais (entram no backup). */
class SharedPrefsDestinationSettings(context: Context, private val logger: AppLogger) : DestinationSettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): DestinationSettings = try {
        val default = DestinationSettings()
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull()
        val lng = prefs.getString(KEY_LNG, null)?.toDoubleOrNull()
        DestinationSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            base = if (lat != null && lng != null) GeoPoint(lat, lng, 0f, 0) else default.base,
            baseIsDefault = lat == null || lng == null,
            baseCity = prefs.getString(KEY_CITY, null) ?: default.baseCity,
            farKm = prefs.getString(KEY_FAR, null)?.toBigDecimalOrNull() ?: default.farKm,
            avoid = prefs.getString(KEY_AVOID, null).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao ler configurações do destino", e)
        DestinationSettings()
    }

    override fun save(settings: DestinationSettings) {
        try {
            val editor = prefs.edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putString(KEY_CITY, settings.baseCity)
                .putString(KEY_FAR, settings.farKm.toPlainString())
                .putString(KEY_AVOID, settings.avoid.joinToString(", "))
            if (settings.baseIsDefault) {
                editor.remove(KEY_LAT).remove(KEY_LNG)
            } else {
                editor.putString(KEY_LAT, settings.base.latitude.toString()).putString(KEY_LNG, settings.base.longitude.toString())
            }
            editor.apply()
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao gravar configurações do destino", e)
        }
    }

    private companion object {
        const val TAG = "DestinationSettings"
        const val PREFS_NAME = "minha_corrida_prefs"
        const val KEY_ENABLED = "dest_enabled"
        const val KEY_LAT = "dest_base_lat"
        const val KEY_LNG = "dest_base_lng"
        const val KEY_CITY = "dest_base_city"
        const val KEY_FAR = "dest_far_km"
        const val KEY_AVOID = "dest_avoid"
    }
}
