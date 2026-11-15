package br.com.minhacorrida.core

import br.com.minhacorrida.core.model.OverlayTimeout
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.overlay.OverlayVisibilityPolicy
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayVisibilityPolicyTest {
    private val offerA = RideOffer(Platform.UBER, BigDecimal("8.91"), BigDecimal("2.3"), BigDecimal("6.6"))
    private val offerB = RideOffer(Platform.UBER, BigDecimal("15.00"), BigDecimal("5"), BigDecimal("1"))

    @Test
    fun `oferta fechada nao reaparece enquanto esta na tela, mas uma nova aparece`() {
        val policy = OverlayVisibilityPolicy()
        assertTrue(policy.shouldShow(offerA, 0))
        policy.onDismissed(offerA)
        assertFalse(policy.shouldShow(offerA, 100))
        assertTrue(policy.shouldShow(offerB, 200))
    }

    @Test
    fun `pedido de 15-11 - oferta reaberta mostra o card de novo`() {
        val policy = OverlayVisibilityPolicy()
        policy.onDismissed(offerA)
        assertFalse(policy.shouldShow(offerA, 1_000))
        policy.onOfferGone(2_000)                                  // saiu da tela
        assertFalse(policy.shouldShow(offerA, 2_500))              // falha de leitura de meio segundo: não reabre
        policy.onOfferGone(3_000)
        assertTrue(policy.shouldShow(offerA, 6_000))               // fora 3 s e voltou: reabriu
        policy.onDismissed(offerA)
        assertTrue(policy.shouldShow(offerB, 7_000))               // outra oferta entrou…
        assertTrue(policy.shouldShow(offerA, 10_000))              // …e a primeira voltou depois: aparece
    }

    @Test
    fun `limites do tempo de exibicao`() {
        assertTrue(OverlayTimeout.isValid(OverlayTimeout.DEFAULT_SECONDS))
        assertFalse(OverlayTimeout.isValid(2))
        assertFalse(OverlayTimeout.isValid(121))
    }
}
