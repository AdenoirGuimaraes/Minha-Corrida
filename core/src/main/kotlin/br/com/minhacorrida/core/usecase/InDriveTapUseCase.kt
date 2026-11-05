package br.com.minhacorrida.core.usecase

import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.history.RideHistoryRepository
import br.com.minhacorrida.core.history.RideRecord
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository

/** O que fazer depois de um toque do motorista no pedido do inDrive. */
sealed class InDriveTap {
    /**
     * Tocou num valor ("Aceitar por R$ 15", "R$ 17,50"): card refeito com esse valor.
     * @property saved corrida guardada no histórico (só se ficou verde), ou null.
     */
    data class NewFare(val analysis: RideAnalysis, val saved: RideRecord?) : InDriveTap()

    /** Tocou em Pular/Fechar: pedido recusado; [removedId] = corrida apagada do histórico, se havia. */
    data class Skipped(val removedId: String?) : InDriveTap()

    /** Toque que não é valor nem recusa (ex.: "Ofereça sua tarifa"), ou sem pedido aberto. */
    object Ignored : InDriveTap()
}

/**
 * Decide pelo botão que o motorista tocou no pedido do inDrive (o app confia no toque):
 * - valor ("Aceitar por R$ 15" ou contraproposta "R$ 17,50") → refaz o card com esse valor
 *   e guarda no histórico SÓ se ficar verde; um toque em outro valor troca a corrida guardada;
 * - "Pular"/"Fechar"/"Recusar" → nada fica guardado (apaga o que esse pedido tinha guardado).
 */
class InDriveTapUseCase(
    private val evaluate: EvaluateRideUseCase,
    private val settings: SettingsRepository,
    private val record: RecordRideUseCase,
    private val repository: RideHistoryRepository,
    private val logger: AppLogger,
) {
    /**
     * @param buttonText texto do botão tocado.
     * @param pending pedido do inDrive na tela (lido do card original), ou null.
     * @param savedId corrida que este pedido já guardou (toque anterior), ou null.
     */
    fun onTap(buttonText: String, pending: RideAnalysis?, savedId: String?): InDriveTap = try {
        val text = buttonText.trim()
        when {
            SKIP.matches(text) -> InDriveTap.Skipped(removeIfNotDone(savedId))
            pending == null || pending.offer.platform != Platform.INDRIVE -> InDriveTap.Ignored
            else -> fareOf(text)?.let { fare -> newFare(pending, fare, savedId) } ?: InDriveTap.Ignored
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao tratar toque no inDrive", e)
        InDriveTap.Ignored
    }

    private fun newFare(pending: RideAnalysis, fare: java.math.BigDecimal, savedId: String?): InDriveTap {
        val rules = RideRules(
            minValuePerKm = settings.getMinValuePerKm(),
            minPassengerRating = settings.getMinPassengerRating(),
            operatingCost = settings.getOperatingCost(),
        )
        val analysis = evaluate(pending.offer.copy(fare = fare), rules)
        // Trocou de ideia (ex.: R$ 17,50 e depois R$ 18): a escolha anterior sai do histórico.
        removeIfNotDone(savedId)
        val saved = record(analysis, originalFare = pending.offer.fare)
        return InDriveTap.NewFare(analysis, saved)
    }

    private fun removeIfNotDone(id: String?): String? {
        if (id == null) return null
        val existing = repository.findById(id) ?: return null
        if (existing.done) return null
        repository.delete(id)
        return id
    }

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
