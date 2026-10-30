package br.com.minhacorrida.core

import br.com.minhacorrida.core.port.SettingsRepository
import java.math.BigDecimal

/** Textos reais das capturas de tela enviadas (ordem de leitura aproximada). */
object Fixtures {
    val UBER_OFFER = listOf(
        "UberX", "R$ 8,91", "5,00 (12)",
        "12 min (6.6 km)", "R. Ábramo Baú, Do Tributo, Lages",
        "5 minutos (2.3 km)", "Rua 31 de Março, 953, Guarujá, Lages", "Aceitar",
    )

    val INDRIVE_LIST = listOf(
        "Online", "Géssica", "5.0(4)", "Agora mesmo",
        "R$ 1/km", "~7,0 km", "R$ 10", "Preço justo",
        "Rua Horacides Pereira Machado 635 (Guarujá)", "Havan Lages 2 (Guarujá, Lages - SC)",
        "Maquininha de cartão", "Pedidos de viagem", "Desempenho",
    )

    val INDRIVE_DETAIL = listOf(
        "Pedido de viagem", "7,3 km", "6 min", "3,3 km",
        "Géssica", "5.0 (4)", "45 seg.", "R$ 1/km ~7,3 km", "R$ 10", "Preço justo",
        "Rua Horacides Pereira Machado 635 (Guarujá)", "Havan Lages 2 (Guarujá, Lages - SC)",
        "Aceitar por R$ 10", "Ofereça sua tarifa", "R$ 11,50", "R$ 12", "Fechar",
    )

    class FakeSettings(private var min: BigDecimal = BigDecimal("1.50")) : SettingsRepository {
        override fun getMinValuePerKm() = min
        override fun setMinValuePerKm(value: BigDecimal) { min = value }
    }
}
