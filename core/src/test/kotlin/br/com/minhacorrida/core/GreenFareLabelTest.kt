package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.GreenFareLabel
import br.com.minhacorrida.core.model.Platform
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pedido de 15/11: o quadrado Mín. verde diz qual mínimo decidiu e o que dá nesse valor. */
class GreenFareLabelTest {
    @Test
    fun `corrida longa - decide o R$ por km e mostra o lucro nesse valor`() {
        // inDrive R$ 22,00 (era 15,00): 9,5 km, 24 min, gasto 8,69, taxa 10%.
        val text = GreenFareLabel.text(
            BigDecimal("21.12"), Platform.INDRIVE, BigDecimal("10"), BigDecimal("9.5"), BigDecimal("8.69"),
            BigDecimal("2.00"), BigDecimal("6.00"), minutes = 24, minutesEstimated = true,
        )
        // Mostrado R$ 22,00 → líquido 19,80 − 8,69 = 11,11; 11,11 ÷ 24 = 0,46/min.
        assertEquals("Pelo R$ 2,00/km\nLucro R$ 11,11\n≈ R$ 0,46/min", text)
    }

    @Test
    fun `corrida curta - decide o lucro e mostra o R$ por km nesse valor`() {
        // Uber 4 km, gasto 3,66: lucro 6,00 pede R$ 9,66 (> R$ 8,00 do R$/km).
        assertEquals(GreenFareLabel.Rule.PROFIT, GreenFareLabel.rule(BigDecimal("4"), BigDecimal.ZERO, BigDecimal("2.00"), BigDecimal("3.66"), BigDecimal("6.00")))
        val text = GreenFareLabel.text(
            BigDecimal("9.66"), Platform.UBER, BigDecimal.ZERO, BigDecimal("4"), BigDecimal("3.66"),
            BigDecimal("2.00"), BigDecimal("6.00"), minutes = null, minutesEstimated = false,
        )
        assertEquals("Pelo lucro R$ 6,00\nR$ 2,42/km", text)              // 9,66 ÷ 4 km
    }
}
