package br.com.minhacorrida.core.model

import java.math.BigDecimal

/**
 * Oferta de corrida lida da tela.
 *
 * @property fare valor oferecido pela corrida (R$).
 * @property tripKm distância do embarque ao destino.
 * @property pickupKm distância até o passageiro; null quando a tela não informa.
 * @property passengerRating nota do passageiro (1 a 5); null quando não aparece (ex.: novo).
 * @property pickupMinutes minutos até o passageiro ("5 min (1.8 km)"); null quando a tela não mostra.
 * @property tripMinutes minutos da viagem ("6 minutos (2.3 km)"); null quando a tela não mostra.
 */
data class RideOffer(
    val platform: Platform,
    val fare: BigDecimal,
    val tripKm: BigDecimal,
    val pickupKm: BigDecimal?,
    val passengerRating: BigDecimal? = null,
    val pickupMinutes: Int? = null,
    val tripMinutes: Int? = null,
) {
    init {
        require(fare.signum() > 0) { "Valor da corrida deve ser positivo: $fare" }
        require(tripKm.signum() > 0) { "Distância da viagem deve ser positiva: $tripKm" }
        require(pickupKm == null || pickupKm.signum() >= 0) { "Distância de busca inválida: $pickupKm" }
    }

    /**
     * Mesma corrida (app, valor e distâncias), ignorando a nota — o OCR às vezes
     * lê a nota numa leitura e não na outra, e isso não pode reabrir o card nem repetir a voz.
     */
    fun sameRideAs(other: RideOffer?): Boolean =
        other != null && platform == other.platform && sameValue(fare, other.fare) &&
            sameValue(tripKm, other.tripKm) && sameValue(pickupKm, other.pickupKm)

    private fun sameValue(a: BigDecimal?, b: BigDecimal?): Boolean =
        if (a == null || b == null) a == b else a.compareTo(b) == 0
}
