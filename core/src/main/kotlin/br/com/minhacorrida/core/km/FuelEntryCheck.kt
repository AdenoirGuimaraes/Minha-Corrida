package br.com.minhacorrida.core.km

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Confere o abastecimento antes de salvar (pedido de 16/11: no relatório apareceu 12,8 L por
 * R$ 6,38 — R$ 0,50 o litro —, o valor do litro digitado no lugar do total pago).
 */
object FuelEntryCheck {
    /** Faixa plausível do preço do litro (gasolina/etanol/diesel no Brasil). */
    val MIN_PRICE_PER_LITER: BigDecimal = BigDecimal("3.00")
    val MAX_PRICE_PER_LITER: BigDecimal = BigDecimal("12.00")

    /**
     * @return o total sugerido (litros × valor) quando o valor digitado parece ser o preço do
     *   litro; null quando o valor parece mesmo ser o total pago.
     */
    fun suggestedTotal(liters: BigDecimal, typedValue: BigDecimal): BigDecimal? {
        if (liters.signum() <= 0 || typedValue.signum() <= 0) return null
        val perLiter = typedValue.divide(liters, 2, RoundingMode.HALF_UP)
        val looksLikePerLiter = typedValue >= MIN_PRICE_PER_LITER && typedValue <= MAX_PRICE_PER_LITER
        return if (perLiter < MIN_PRICE_PER_LITER && looksLikePerLiter) {
            (liters * typedValue).setScale(2, RoundingMode.HALF_UP)
        } else {
            null
        }
    }
}
