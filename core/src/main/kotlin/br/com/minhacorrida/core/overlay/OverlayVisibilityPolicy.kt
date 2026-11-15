package br.com.minhacorrida.core.overlay

import br.com.minhacorrida.core.model.RideOffer

/**
 * Regra de exibição do leitor.
 * - Depois de fechado (por toque ou por tempo), a mesma oferta NÃO reaparece enquanto continua
 *   na tela (o app de corrida segue disparando eventos de tela).
 * - Se a oferta SAIR da tela por pelo menos [reopenAfterMs] (ou outra oferta aparecer) e depois
 *   voltar — o motorista reabriu a oferta —, o card aparece de novo (pedido de 15/11).
 *   O intervalo mínimo evita que uma leitura falha de um instante reabra o card recém-fechado.
 */
class OverlayVisibilityPolicy(private val reopenAfterMs: Long = REOPEN_AFTER_MS) {

    private var dismissedOffer: RideOffer? = null
    /** Desde quando a oferta fechada não está mais na tela; null = ainda na tela. */
    private var goneSince: Long? = null

    /**
     * @param nowMillis relógio monotônico (ex.: SystemClock.elapsedRealtime()).
     * @return true se o card desta oferta deve aparecer.
     */
    fun shouldShow(offer: RideOffer, nowMillis: Long): Boolean {
        val dismissed = dismissedOffer ?: return true
        if (!offer.sameRideAs(dismissed)) {
            // Outra oferta na tela: a fechada saiu.
            onOfferGone(nowMillis)
            return true
        }
        val gone = goneSince
        if (gone != null && nowMillis - gone >= reopenAfterMs) {
            // Reaberta depois de sair da tela: mostra de novo.
            dismissedOffer = null
            goneSince = null
            return true
        }
        goneSince = null // continua na tela (ou sumiu só por um instante)
        return false
    }

    /** Nenhuma oferta na tela agora. */
    fun onOfferGone(nowMillis: Long) {
        if (dismissedOffer != null && goneSince == null) goneSince = nowMillis
    }

    /** Registra que esta oferta foi fechada (toque ou tempo). */
    fun onDismissed(offer: RideOffer) {
        dismissedOffer = offer
        goneSince = null
    }

    companion object {
        /** Fora da tela por 2,5 s já conta como "reaberta" quando voltar. */
        const val REOPEN_AFTER_MS = 2_500L
    }
}
