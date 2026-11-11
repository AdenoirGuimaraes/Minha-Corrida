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
        assertEquals("🎉 Meta batida! Passou R$ 20,00", formatter.format(p).status)
    }

    @Test
    fun `sem deducoes falta e o proprio lucro e sem meta so mostra as deducoes`() {
        val semDeducao = DailyGoalCalculator.progress(BigDecimal("50"), emptyList(), BigDecimal("150"))
        assertEquals("Falta R$ 100,00 de lucro líquido", formatter.format(semDeducao).status)

        val semMeta = formatter.format(DailyGoalCalculator.progress(BigDecimal("50"), listOf(dizimo), null))
        assertNull(semMeta.title)
        assertNull(semMeta.status)
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

    // Print de 10/11: "Separar p/ abastecer R$ 16,18" somava combustível e manutenção sem dizer.
    @Test
    fun `separar mostra cada gasto e cada deducao e o total inclui o dizimo`() {
        val page = br.com.minhacorrida.core.history.RideHistoryPage(
            period = br.com.minhacorrida.core.history.HistoryPeriod.DAY,
            range = br.com.minhacorrida.core.history.DateRange(java.time.LocalDate.of(2026, 11, 10), java.time.LocalDate.of(2026, 11, 10)),
            rides = emptyList(),
            summary = br.com.minhacorrida.core.history.RideHistorySummary(
                doneCount = 5, totalKm = BigDecimal("17.7"), totalFare = BigDecimal("59.70"),
                fuelCost = BigDecimal("10.87"), maintenanceCost = BigDecimal("5.31"),
                netProfit = BigDecimal("43.52"), ridesWithoutCost = 0,
            ),
        )
        val history = br.com.minhacorrida.core.format.RideHistoryFormatter()
        val progress = DailyGoalCalculator.progress(BigDecimal("43.52"), listOf(dizimo), null)
        assertEquals("R$ 20,53", history.setAside(page, progress))   // 10,87 + 5,31 + 4,35
        assertEquals(
            "⛽ Gasolina das corridas (17,7 km) R$ 10,87\n🔧 Manutenção das corridas R$ 5,31\n📌 Dízimo (10%) R$ 4,35\nRecebido R$ 59,70",
            history.setAsideDetails(page, progress),
        )
        assertEquals("R$ 39,17", history.kept(progress))            // número grande: 43,52 − 4,35
        val semDeducao = DailyGoalCalculator.progress(BigDecimal("43.52"), emptyList(), null)
        assertEquals("R$ 16,18", history.setAside(page, semDeducao))
        assertEquals(
            "⛽ Gasolina das corridas (17,7 km) R$ 10,87\n🔧 Manutenção das corridas R$ 5,31\nRecebido R$ 59,70",
            history.setAsideDetails(page, semDeducao),
        )
    }
}
