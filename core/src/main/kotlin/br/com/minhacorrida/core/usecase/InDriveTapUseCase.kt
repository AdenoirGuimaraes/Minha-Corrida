package br.com.minhacorrida.core.usecase

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository

/** O que fazer depois de um toque do motorista no pedido do inDrive. */
sealed class InDriveTap {
    /** Tocou num valor ("Aceitar por R$ 15", "R$ 17,50"): card refeito com esse valor. */
    data class NewFare(val analysis: RideAnalysis) : InDriveTap()

    /** Tocou em Pular/Fechar: pedido recusado (o card some). */
    object Skipped : InDriveTap()

    /** Toque que não é valor nem recusa (ex.: "Ofereça sua tarifa"), ou sem pedido aberto. */
    object Ignored : InDriveTap()
}

/**
 * Refaz o card pelo botão tocado no pedido do inDrive:
 * - valor ("Aceitar por R$ 15" ou contraproposta "R$ 17,50") → card com esse valor;
 * - "Pular"/"Fechar"/"Recusar" → pedido recusado.
 *
 * O toque NÃO grava no histórico: oferta e contraproposta dependem do passageiro.
 * A corrida é gravada quando a tela confirma que ela é sua (ver [RideLifecycleUseCase]).
 */
class InDriveTapUseCase(
    private val evaluate: EvaluateRideUseCase,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
) {
    /**
     * @param buttonText texto do botão tocado.
     * @param pending pedido do inDrive na tela (lido do card original), ou null.
     */
    fun onTap(buttonText: String, pending: RideAnalysis?): InDriveTap = try {
        val text = buttonText.trim()
        when {
            SKIP.matches(text) -> InDriveTap.Skipped
            pending == null || pending.offer.platform != Platform.INDRIVE -> InDriveTap.Ignored
            else -> fareOf(text)?.let { fare -> InDriveTap.NewFare(evaluate(pending.offer.copy(fare = fare), rules())) }
                ?: InDriveTap.Ignored
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao tratar toque no inDrive", e)
        InDriveTap.Ignored
    }

    private fun rules() = RideRules.from(settings)

    /** "Aceitar por R$ 15", "R$ 17,50", "Oferecer R$ 19" → valor; "R$ 2,1/km" e textos longos não. */
    private fun fareOf(text: String): java.math.BigDecimal? {
        if (text.length > MAX_BUTTON_TEXT) return null
        val match = MONEY.find(text) ?: return null
        if (text.substring(match.range.last + 1).trimStart().startsWith("/")) return null
        return NumberParser.parse(match.groupValues[1])?.takeIf { it.signum() > 0 }
    }

    private companion object {
        const val TAG = "InDriveTap"
        const val MAX_BUTTON_TEXT = 30
        val SKIP = Regex("""(?i)pular|fechar|recusar|ignorar|cancelar""")
        val MONEY = Regex("""R\$\s*(\d+(?:[.,]\d{1,2})?)""")
    }
}
