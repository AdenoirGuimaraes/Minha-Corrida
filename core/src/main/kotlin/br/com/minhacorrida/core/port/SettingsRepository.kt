package br.com.minhacorrida.core.port

import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
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

    /** Custos do carro para o lucro real líquido; null enquanto não informados. */
    fun getOperatingCost(): OperatingCost?

    fun setOperatingCost(cost: OperatingCost?)

    /** Nota mínima do passageiro; null = sem filtro de nota. */
    fun getMinPassengerRating(): BigDecimal?

    /** @throws IllegalArgumentException fora de 1 a 5. */
    fun setMinPassengerRating(rating: BigDecimal?)

    /** Apps que o Minha Corrida lê e informa (padrão: todos). */
    fun getEnabledPlatforms(): Set<Platform>

    fun setEnabledPlatforms(platforms: Set<Platform>)

    /** Voz "Aceitar corrida…" para corridas verdes (padrão: ligada). */
    fun isVoiceEnabled(): Boolean

    fun setVoiceEnabled(enabled: Boolean)
}
