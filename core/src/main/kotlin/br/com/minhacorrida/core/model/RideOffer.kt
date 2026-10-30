package br.com.minhacorrida.core.model

import java.math.BigDecimal

/**
 * Oferta de corrida lida da tela.
 *
 * @property fare valor oferecido pela corrida (R$).
 * @property tripKm distância do embarque ao destino.
 * @property pickupKm distância até o passageiro; null quando a tela não informa.
 */
data class RideOffer(
    val platform: Platform,
    val fare: BigDecimal,
    val tripKm: BigDecimal,
    val pickupKm: BigDecimal?,
) {
    init {
        require(fare.signum() > 0) { "Valor da corrida deve ser positivo: $fare" }
        require(tripKm.signum() > 0) { "Distância da viagem deve ser positiva: $tripKm" }
        require(pickupKm == null || pickupKm.signum() >= 0) { "Distância de busca inválida: $pickupKm" }
    }
}
