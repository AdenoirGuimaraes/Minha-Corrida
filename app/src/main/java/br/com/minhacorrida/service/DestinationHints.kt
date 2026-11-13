package br.com.minhacorrida.service

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import br.com.minhacorrida.core.destination.DestinationAdvisor
import br.com.minhacorrida.core.destination.DestinationSettingsRepository
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.model.Place
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.AppLogger
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Linha do destino no card: primeiro o que dá para saber só pelo texto (bairro, fora da cidade,
 * bairro a evitar); depois, com o endereço localizado pelo Geocoder do Android (em segundo
 * plano, com cache), a distância da volta até a base e o lucro com a volta.
 */
class DestinationHints(
    context: Context,
    private val settings: DestinationSettingsRepository,
    private val logger: AppLogger,
    private val show: (RideOffer, String?) -> Unit,
) {
    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale("pt", "BR")) else null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<String, GeoPoint?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GeoPoint?>?) = size > MAX_CACHE
    }
    private var lastOffer: RideOffer? = null
    /** Última linha calculada: o card reaparecendo (mesma oferta) recebe de novo, sem recalcular. */
    @Volatile private var lastText: String? = null

    /** Chamar sempre que o card de uma oferta é mostrado; calcula só na 1ª vez de cada oferta. */
    fun onOfferShown(analysis: RideAnalysis) {
        try {
            val offer = analysis.offer
            if (offer.sameRideAs(lastOffer)) {
                show(offer, lastText)
                return
            }
            lastOffer = offer
            lastText = null
            val config = settings.get()
            val place = offer.destination
            val profit = analysis.profit
            val first = DestinationAdvisor.advise(place, null, profit?.costPerKm, profit?.netProfit, config)
            lastText = first?.text
            show(offer, first?.text)
            if (first == null || place == null || geocoder == null) return
            worker.execute {
                val point = locate(place, config.baseCity)
                val returnKm = point?.let { DestinationAdvisor.returnKm(it, config.base) }
                val full = DestinationAdvisor.advise(place, returnKm, profit?.costPerKm, profit?.netProfit, config)
                if (returnKm != null) logger.debug(TAG, "Destino ${place.neighborhood}: volta ≈ $returnKm km")
                main.post {
                    if (offer.sameRideAs(lastOffer)) lastText = full?.text
                    show(offer, full?.text)
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Falha na linha do destino", e)
        }
    }

    /** Endereço → ponto no mapa (cache por texto). Sem internet ou sem resultado: null. */
    @Suppress("DEPRECATION")
    private fun locate(place: Place, baseCity: String): GeoPoint? {
        val query = buildString {
            append(place.raw)
            if (place.city == null) append(", ").append(baseCity)
            append(", SC, Brasil")
        }
        synchronized(cache) { if (cache.containsKey(query)) return cache[query] }
        val point = try {
            geocoder?.getFromLocationName(query, 1)?.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude, 0f, 0) }
        } catch (e: Exception) {
            logger.warn(TAG, "Endereço não localizado: ${place.neighborhood}", e)
            null
        }
        synchronized(cache) { cache[query] = point }
        return point
    }

    private companion object {
        const val TAG = "DestinationHints"
        const val MAX_CACHE = 200
    }
}
