package br.com.minhacorrida.core.port

import java.math.BigDecimal

/** Preferências do motorista usadas na avaliação das corridas. */
interface SettingsRepository {
    /** Valor mínimo aceitável por km rodado (R$/km). */
    fun getMinValuePerKm(): BigDecimal

    /** @throws IllegalArgumentException se o valor não for positivo. */
    fun setMinValuePerKm(value: BigDecimal)
}
