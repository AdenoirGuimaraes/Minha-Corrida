package br.com.minhacorrida.core.format

import br.com.minhacorrida.core.goal.GoalProgress
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Textos do bloco "Meta do dia" da aba Corridas (pt-BR). As deduções ficam no "Separar". */
data class GoalText(
    /** "Meta do dia R$ 150,00 · 55%" ou null sem meta. */
    val title: String?,
    /** "Falta R$ 67,07 no bolso = R$ 74,53 de lucro líquido" / "🎉 Meta batida! Passou R$ 5,00". */
    val status: String?,
    val progressPercent: Int?,
    val reached: Boolean,
)

class GoalFormatter(private val locale: Locale = Locale("pt", "BR")) {

    fun format(progress: GoalProgress): GoalText {
        val goal = progress.goal ?: return GoalText(null, null, null, false)

        val title = "Meta do dia R$ ${money(goal)} · ${progress.progressPercent}%"
        val status = if (progress.reached) {
            val surplus = progress.surplus
            if (surplus.signum() > 0) "🎉 Meta batida! Passou R$ ${money(surplus)}" else "🎉 Meta batida!"
        } else if (progress.deductions.isEmpty()) {
            "Falta R$ ${money(progress.remaining!!)} de lucro líquido"
        } else {
            "Falta R$ ${money(progress.remaining!!)} no bolso = R$ ${money(progress.profitNeeded!!)} de lucro líquido"
        }
        return GoalText(title, status, progress.progressPercent, progress.reached)
    }

    private fun money(value: BigDecimal) = DecimalFormat("#,##0.00", DecimalFormatSymbols(locale)).format(value)
}
