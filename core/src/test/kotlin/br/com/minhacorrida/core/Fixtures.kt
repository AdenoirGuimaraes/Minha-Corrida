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

    /** Oferta aberta do inDrive (print de 30/10): o que a acessibilidade enxerga... */
    val INDRIVE_DETAIL_ACCESSIBILITY = listOf(
        "Pedido de viagem", "Julie", "5.0 (31)", "Agora mesmo",
        "R$ 1,9/km ~1,8 km", "R$ 11", "Preço justo",
        "Rua Dalírio Alves de Souza 5 (Morro do Posto)", "Rua Agostinho Farias Neto (Passo Fundo, Lages - SC)",
        "Aceitar por R$ 11", "Ofereça sua tarifa", "R$ 13", "R$ 13,50", "Fechar",
    )

    /** ...e o que o OCR lê no mapa (bolhas desenhadas como imagem). */
    val INDRIVE_MAP_OCR = listOf(
        "Pedido de viagem", "8 min", "3,4 km", "CONTA", "DINHEIRO", "282", "6 min", "1,8 km", "Google",
    )

    class FakeSettings(
        private var min: BigDecimal = BigDecimal("1.50"),
        private var timeout: Int = 10,
    ) : SettingsRepository {
        override fun getMinValuePerKm() = min
        override fun setMinValuePerKm(value: BigDecimal) { min = value }
        override fun getOverlayTimeoutSeconds() = timeout
        override fun setOverlayTimeoutSeconds(seconds: Int) { timeout = seconds }
    }
}
