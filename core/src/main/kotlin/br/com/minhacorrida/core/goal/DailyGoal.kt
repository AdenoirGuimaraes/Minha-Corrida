package br.com.minhacorrida.core.goal

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Parte do lucro líquido que o motorista separa (ex.: "Dízimo" 10%).
 * Calculada sobre o lucro líquido — o que sobra depois de combustível e manutenção.
 *
 * @throws IllegalArgumentException se a porcentagem não ficar entre 0 (exclusive) e 100 (exclusive).
 */
data class ProfitDeduction(val description: String, val percent: BigDecimal) {
    init {
        require(percent.signum() > 0 && percent < HUNDRED) { "Dedução deve ficar entre 0 e 100%" }
    }

    /** Descrição mostrada ("Dedução" quando o motorista deixou em branco). */
    val label: String get() = description.trim().ifEmpty { DEFAULT_LABEL }

    companion object {
        const val DEFAULT_LABEL = "Dedução"
        internal val HUNDRED = BigDecimal(100)
    }
}

/** Meta diária e deduções do lucro (configurações separadas das regras do card: ISP). */
interface GoalSettingsRepository {
    /** Quanto o motorista quer que fique com ele por dia (R$), já sem gastos e deduções. null = sem meta. */
    fun getDailyGoal(): BigDecimal?

    /** @throws IllegalArgumentException se não for positivo. */
    fun setDailyGoal(value: BigDecimal?)

    /** Deduções do lucro líquido, na ordem em que foram digitadas (vazia = nenhuma). */
    fun getProfitDeductions(): List<ProfitDeduction>

    /** @throws IllegalArgumentException se a soma chegar a 100% ou mais. */
    fun setProfitDeductions(deductions: List<ProfitDeduction>)
}

/** Valor de uma dedução no período. */
data class DeductionAmount(val deduction: ProfitDeduction, val amount: BigDecimal)

/**
 * Situação do lucro do período diante das deduções e da meta.
 *
 * @property netProfit lucro líquido (corridas realizadas − combustível − manutenção).
 * @property kept o que fica com o motorista: [netProfit] − deduções.
 * @property remaining quanto falta para a meta, no bolso (null sem meta; zero quando batida).
 * @property profitNeeded quanto ainda precisa de LUCRO LÍQUIDO para chegar à meta, já contando
 *   que as deduções levam a parte delas: [remaining] ÷ (1 − soma das %). null sem meta.
 * @property progressPercent 0 a 100 (null sem meta).
 */
data class GoalProgress(
    val netProfit: BigDecimal,
    val deductions: List<DeductionAmount>,
    val totalDeductions: BigDecimal,
    val kept: BigDecimal,
    val goal: BigDecimal?,
    val remaining: BigDecimal?,
    val profitNeeded: BigDecimal?,
    val progressPercent: Int?,
) {
    val reached: Boolean get() = goal != null && kept >= goal

    /** Quanto passou da meta (zero se não bateu ou sem meta). */
    val surplus: BigDecimal get() = if (goal != null && kept > goal) kept - goal else BigDecimal.ZERO.setScale(2)
}

/**
 * Faz as contas da meta diária. Ex. (10/11): lucro R$ 92,15, dízimo 10%, meta R$ 150:
 * dízimo R$ 9,22 → fica R$ 82,93 → faltam R$ 67,07 no bolso = R$ 74,53 de lucro líquido
 * (67,07 ÷ 0,90, arredondado para cima).
 */
object DailyGoalCalculator {

    /**
     * @param netProfit lucro líquido do período (já sem combustível e manutenção).
     * @param deductions deduções configuradas (sobre o lucro positivo; prejuízo não gera dedução).
     * @param goal meta do período; null = só as deduções.
     */
    fun progress(netProfit: BigDecimal, deductions: List<ProfitDeduction>, goal: BigDecimal?): GoalProgress {
        val profit = netProfit.setScale(2, RoundingMode.HALF_UP)
        val base = profit.max(BigDecimal.ZERO)
        val amounts = deductions.map { d ->
            DeductionAmount(d, (base * d.percent).divide(ProfitDeduction.HUNDRED, 2, RoundingMode.HALF_UP))
        }
        val totalDeductions = amounts.fold(BigDecimal.ZERO.setScale(2)) { acc, a -> acc + a.amount }
        val kept = profit - totalDeductions
        if (goal == null || goal.signum() <= 0) {
            return GoalProgress(profit, amounts, totalDeductions, kept, null, null, null, null)
        }
        val remaining = (goal - kept).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        val keepShare = (ProfitDeduction.HUNDRED - totalPercent(deductions))
            .divide(ProfitDeduction.HUNDRED, 6, RoundingMode.HALF_UP)
        val profitNeeded = remaining.divide(keepShare, 2, RoundingMode.CEILING)
        val percent = (kept.max(BigDecimal.ZERO) * ProfitDeduction.HUNDRED)
            .divide(goal, 0, RoundingMode.DOWN).toInt().coerceIn(0, 100)
        return GoalProgress(profit, amounts, totalDeductions, kept, goal, remaining, profitNeeded, percent)
    }

    /** Soma das porcentagens. */
    fun totalPercent(deductions: List<ProfitDeduction>): BigDecimal =
        deductions.fold(BigDecimal.ZERO) { acc, d -> acc + d.percent }

    /** Deduções somando menos de 100% (senão não sobraria nada). */
    fun isValid(deductions: List<ProfitDeduction>): Boolean = totalPercent(deductions) < ProfitDeduction.HUNDRED
}
