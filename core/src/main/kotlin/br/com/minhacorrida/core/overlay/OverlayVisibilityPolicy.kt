package br.com.minhacorrida.core.overlay

import br.com.minhacorrida.core.model.RideOffer

/**
 * Regra de exibição do leitor: depois de fechado (por toque ou por tempo),
 * a mesma oferta não reaparece — só uma oferta diferente reabre o leitor.
 * Necessário porque o app de corrida continua disparando eventos de tela.
 */
class OverlayVisibilityPolicy {

    private var dismissedOffer: RideOffer? = null

    /** @return true se a oferta ainda não foi fechada pelo motorista ou pelo tempo. */
    fun shouldShow(offer: RideOffer): Boolean = offer != dismissedOffer

    /** Registra que esta oferta foi fechada. */
    fun onDismissed(offer: RideOffer) {
        dismissedOffer = offer
    }
}
