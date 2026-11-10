package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.GoalFormatter
import br.com.minhacorrida.core.goal.DailyGoalCalculator
import br.com.minhacorrida.core.goal.ProfitDeduction
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedido de 10/11: meta diária e dízimo de 10% sobre o lucro líquido. */
class DailyGoalTest {
    private val dizimo = ProfitDeduction("Dízimo", BigDecimal("10"))
    private val formatter = GoalFormatter()

    @Test
    fun `meta com dizimo - lucro 92,15 do print de 08-11 e meta de 150`() {
        val p = DailyGoalCalculator.progress(BigDecimal("92.15"), listOf(dizimo), BigDecimal("150"))
        assertEquals("9.22", p.deductions.single().amount.toPlainString())
        assertEquals("82.93", p.kept.toPlainString())
        assertEquals("67.07", p.remaining!!.toPlainString())
        assertEquals("74.53", p.profitNeeded!!.toPlainString()) // 67,07 ÷ 0,90 para cima
        assertEquals(55, p.progressPercent)
        assertFalse(p.reached)

        val t = formatter.format(p)
        assertEquals("Meta do dia R$ 150,00 · 55%", t.title)
        assertEquals("Dízimo 10%: R$ 9,22\nFica com você R$ 82,93", t.deductions)
        assertEquals("Falta R$ 67,07 no bolso = R$ 74,53 de lucro líquido", t.status)
    }

    @Test
    fun `lucro necessario realmente bate a meta`() {
        val p = DailyGoalCalculator.progress(BigDecimal("92.15"), listOf(dizimo), BigDecimal("150"))
        val depois = DailyGoalCalculator.progress(p.netProfit + p.profitNeeded!!, listOf(dizimo), BigDecimal("150"))
        assertTrue(depois.reached)
    }

    @Test
    fun `varias deducoes somam`() {
        val p = DailyGoalCalculator.progress(
            BigDecimal("200"),
            listOf(dizimo, ProfitDeduction("", BigDecimal("5"))),
            BigDecimal("150"),
        )
        assertEquals("30.00", p.totalDeductions.toPlainString())
        assertEquals("170.00", p.kept.toPlainString())
        assertTrue(p.reached)
        assertEquals(100, p.progressPercent)
        assertEquals("Dízimo 10%: R$ 20,00 · Dedução 5%: R$ 10,00\nFica com você R$ 170,00", formatter.format(p).deductions)
        assertEquals("🎉 Meta batida! Passou R$ 20,00", formatter.format(p).status)
    }

    @Test
    fun `sem deducoes falta e o proprio lucro e sem meta so mostra as deducoes`() {
        val semDeducao = DailyGoalCalculator.progress(BigDecimal("50"), emptyList(), BigDecimal("150"))
        assertEquals("Falta R$ 100,00 de lucro líquido", formatter.format(semDeducao).status)
        assertNull(formatter.format(semDeducao).deductions)

        val semMeta = formatter.format(DailyGoalCalculator.progress(BigDecimal("50"), listOf(dizimo), null))
        assertNull(semMeta.title)
        assertEquals("Dízimo 10%: R$ 5,00\nFica com você R$ 45,00", semMeta.deductions)
    }

    @Test
    fun `prejuizo nao gera deducao`() {
        val p = DailyGoalCalculator.progress(BigDecimal("-3.50"), listOf(dizimo), BigDecimal("100"))
        assertEquals("0.00", p.totalDeductions.toPlainString())
        assertEquals(0, p.progressPercent)
    }

    @Test
    fun `deducao invalida e soma de 100 por cento sao recusadas`() {
        assertFailsWith<IllegalArgumentException> { ProfitDeduction("x", BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { ProfitDeduction("x", BigDecimal("100")) }
        assertFalse(DailyGoalCalculator.isValid(listOf(ProfitDeduction("a", BigDecimal("60")), ProfitDeduction("b", BigDecimal("40")))))
        assertTrue(DailyGoalCalculator.isValid(listOf(dizimo)))
    }
}
