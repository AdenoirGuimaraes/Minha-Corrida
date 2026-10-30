package br.com.minhacorrida.core.port

import java.math.BigDecimal

/** Preferências do motorista usadas na avaliação e exibição das corridas. */
interface SettingsRepository {
    /** Valor mínimo aceitável por km rodado (R$/km). */
    fun getMinValuePerKm(): BigDecimal

    /** @throws IllegalArgumentException se o valor não for positivo. */
    fun setMinValuePerKm(value: BigDecimal)

    /** Segundos até o leitor fechar sozinho. */
    fun getOverlayTimeoutSeconds(): Int

    /** @throws IllegalArgumentException fora de [br.com.minhacorrida.core.model.OverlayTimeout]. */
    fun setOverlayTimeoutSeconds(seconds: Int)
}
