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
    fun `oferta fechada nao reaparece, mas uma nova aparece`() {
        val policy = OverlayVisibilityPolicy()
        assertTrue(policy.shouldShow(offerA))
        policy.onDismissed(offerA)
        assertFalse(policy.shouldShow(offerA))
        assertTrue(policy.shouldShow(offerB))
    }

    @Test
    fun `limites do tempo de exibicao`() {
        assertTrue(OverlayTimeout.isValid(OverlayTimeout.DEFAULT_SECONDS))
        assertFalse(OverlayTimeout.isValid(2))
        assertFalse(OverlayTimeout.isValid(121))
    }
}
