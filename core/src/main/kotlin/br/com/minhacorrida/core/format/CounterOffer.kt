package br.com.minhacorrida.core.format

import br.com.minhacorrida.core.model.Platform
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Valor do "Mín. verde" mostrado no card. No inDrive ele é a base da contraproposta, então
 * aparece arredondado para cima em reais inteiros (R$ 13,42 → R$ 14,00): fácil de digitar e
 * nunca abaixo do verde. Nas outras plataformas (preço fixo) aparece o valor exato.
 * A cor do card continua usando o valor exato.
 */
object CounterOffer {
    fun displayGreenFare(value: BigDecimal, platform: Platform): BigDecimal =
        if (platform == Platform.INDRIVE) value.setScale(0, RoundingMode.CEILING).setScale(2) else value
}
