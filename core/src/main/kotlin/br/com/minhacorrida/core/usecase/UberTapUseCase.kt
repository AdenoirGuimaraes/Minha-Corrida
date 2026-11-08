package br.com.minhacorrida.core.usecase

import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.history.RideRecord
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.port.AppLogger
import java.text.Normalizer

/** O que fazer depois de um toque do motorista no cartão de oferta da Uber. */
sealed class UberTap {
    /**
     * Tocou em "Aceitar" (Uber em tela cheia) ou "Tenho interesse" (cartão sobre outro app).
     * @property saved corrida guardada agora, ou null (repetida ou sem cartão lido).
     */
    data class Accepted(val analysis: RideAnalysis, val saved: RideRecord?) : UberTap()

    /** Tocou no X (recusar): nada é guardado. */
    object Rejected : UberTap()

    /** Toque que não é aceite nem recusa, ou aceite sem cartão da Uber lido. */
    data class Ignored(val reason: String) : UberTap()
}

/**
 * Decide pelo botão tocado no cartão da Uber (o motorista decide, não a cor do card):
 * - "Aceitar" / "Tenho interesse" → guarda a corrida do cartão, de QUALQUER cor;
 * - X ("Fechar", "Recusar", "Dispensar"…) → nada é guardado;
 * - qualquer outro toque → ignorado (fica só no diagnóstico, para calibrar).
 */
class UberTapUseCase(
    private val record: RecordRideUseCase,
    private val logger: AppLogger,
) {
    /** Tipo do botão pelo texto (ou descrição) que o Android entregou no toque. */
    enum class Kind { ACCEPT, REJECT, OTHER }

    fun classify(buttonText: String): Kind {
        val text = normalize(buttonText)
        return when {
            text.isEmpty() || text.length > MAX_BUTTON_TEXT -> Kind.OTHER
            // Antes do aceite: "Não tenho interesse" contém "tenho interesse".
            REJECT.containsMatchIn(text) -> Kind.REJECT
            ACCEPT.containsMatchIn(text) -> Kind.ACCEPT
            else -> Kind.OTHER
        }
    }

    /**
     * @param buttonText texto do botão tocado.
     * @param card último cartão da Uber analisado (o que estava na tela no toque), ou null.
     */
    fun onTap(buttonText: String, card: RideAnalysis?): UberTap = try {
        when (classify(buttonText)) {
            Kind.REJECT -> UberTap.Rejected
            Kind.OTHER -> UberTap.Ignored("toque que não é Aceitar/Tenho interesse/X")
            Kind.ACCEPT -> if (card == null || card.offer.platform != Platform.UBER) {
                UberTap.Ignored("aceite sem cartão da Uber lido")
            } else {
                UberTap.Accepted(card, record(card, acceptedByDriver = true))
            }
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao tratar toque na Uber", e)
        UberTap.Ignored("erro: ${e.message}")
    }

    /** Minúsculas, sem acentos e sem espaços nas pontas ("ACEITAR " → "aceitar"). */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()

    private companion object {
        const val TAG = "UberTap"
        const val MAX_BUTTON_TEXT = 40
        val DIACRITICS = Regex("""\p{Mn}+""")
        val ACCEPT = Regex("""^(aceitar|tenho interesse)\b""")
        val REJECT = Regex("""^(x|fechar|recusar|rejeitar|dispensar|ignorar|nao tenho interesse|close|dismiss)\b""")
    }
}
