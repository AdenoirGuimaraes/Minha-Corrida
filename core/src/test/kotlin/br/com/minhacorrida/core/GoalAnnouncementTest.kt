package br.com.minhacorrida.core

import br.com.minhacorrida.core.goal.DailyGoalCalculator
import br.com.minhacorrida.core.goal.GoalAnnouncement
import br.com.minhacorrida.core.goal.ProfitDeduction
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pedido de 15/11: depois de cada corrida, a voz fala quanto falta para a meta do dia. */
class GoalAnnouncementTest {
    private val tithe = listOf(ProfitDeduction("Dízimo", BigDecimal("10")))

    @Test
    fun `fala quanto falta, quando bateu e nada sem meta`() {
        // Lucro 52,00 − 10% = 46,80 → faltam 23,20 para 70,00.
        val missing = DailyGoalCalculator.progress(BigDecimal("52.00"), tithe, BigDecimal("70.00"))
        assertEquals("Faltam 23 reais e 20 centavos para a meta do dia.", GoalAnnouncement.text(missing))
        // Lucro 81,45 − 8,15 = 73,30 → passou 3,30 (como no print de 12/11).
        val reached = DailyGoalCalculator.progress(BigDecimal("81.45"), tithe, BigDecimal("70.00"))
        assertEquals("Meta do dia batida! Passou 3 reais e 30 centavos.", GoalAnnouncement.text(reached))
        assertNull(GoalAnnouncement.text(DailyGoalCalculator.progress(BigDecimal("50"), tithe, null)))
    }

    @Test
    fun `valores por extenso para a voz`() {
        assertEquals("1 real", GoalAnnouncement.spoken(BigDecimal("1.00")))
        assertEquals("50 centavos", GoalAnnouncement.spoken(BigDecimal("0.50")))
        assertEquals("70 reais e 1 centavo", GoalAnnouncement.spoken(BigDecimal("70.01")))
        assertEquals("0 reais", GoalAnnouncement.spoken(BigDecimal.ZERO))
    }
}
