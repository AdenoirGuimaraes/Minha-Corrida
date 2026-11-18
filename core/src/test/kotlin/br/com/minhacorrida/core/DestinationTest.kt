package br.com.minhacorrida.core

import br.com.minhacorrida.core.destination.DestinationAdvisor
import br.com.minhacorrida.core.destination.DestinationReader
import br.com.minhacorrida.core.destination.DestinationSettings
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.model.Place
import br.com.minhacorrida.core.parser.UberOfferParser
import br.com.minhacorrida.core.port.NoOpLogger
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedido de 13/11 (itens 1 e 2): destino longe / bairro a evitar e custo da volta vazia. */
class DestinationTest {
    @Test
    fun `Uber - destino depois da 2a distancia, inclusive em duas linhas`() {
        assertEquals(Place("Rua 31 de Março, 953, Guarujá, Lages", "Guarujá", "Lages"), DestinationReader.uber(Fixtures.UBER_OFFER))
        val twoLines = listOf(
            "UberX", "R$ 7,80", "* 4,95 (176)", "9 min (2.2 km)", "Rua Frei Gabriel, Universitário, Lages",
            "6 minutos (1.9 km)", "Rua Juvelina Ribeiro Antunes, 146,", "Caravágio, Lages", "Aceitar",
        )
        val place = DestinationReader.uber(twoLines)!!
        assertEquals("Caravágio", place.neighborhood)
        assertEquals("Lages", place.city)
        assertEquals("Rua Juvelina Ribeiro Antunes, 146, Caravágio, Lages", place.raw)
        // O parser da Uber já entrega o destino junto com a oferta.
        assertEquals("Guarujá", UberOfferParser(NoOpLogger).parse(Fixtures.UBER_OFFER)!!.destination!!.neighborhood)
    }

    @Test
    fun `inDrive - segunda linha de endereco com parenteses`() {
        assertEquals(Place("Havan Lages 2 (Guarujá, Lages - SC)", "Guarujá", "Lages"), DestinationReader.inDrive(Fixtures.INDRIVE_DETAIL))
        assertEquals("Passo Fundo", DestinationReader.inDrive(Fixtures.INDRIVE_DETAIL_ACCESSIBILITY)!!.neighborhood)
        val centro = DestinationReader.inDrive(listOf("Rua A 10 (Santa Helena)", "Unifacvest (Avenida Marechal Floriano - Centro, Lages - SC)"))!!
        assertEquals("Centro", centro.neighborhood)
        assertNull(DestinationReader.inDrive(listOf("Rua A 10 (Santa Helena)")))   // só o embarque
    }

    // (17/11) O texto do destino agora vem do painel do card: ver NeighborhoodTest.

    @Test
    fun `km da volta pela linha reta vezes 1,3`() {
        val base = DestinationSettings.LAGES_CENTER
        val south = GeoPoint(base.latitude - 0.01, base.longitude, 0f, 0)      // ~1,11 km em linha reta
        assertEquals("1.4", DestinationAdvisor.returnKm(south, base).toPlainString())
    }

    @Test
    fun `pedido de 15-11 - busca longa so avisa`() {
        assertEquals(
            "⚠️ Busca longa: 6,9 km (viagem 2,6 km)",
            DestinationAdvisor.pickupWarning(BigDecimal("6.9"), BigDecimal("2.6"), BigDecimal("4.0")),
        )
        assertNull(DestinationAdvisor.pickupWarning(BigDecimal("2.6"), BigDecimal("1.4"), BigDecimal("4.0")))   // dentro do limite
        assertNull(DestinationAdvisor.pickupWarning(BigDecimal("6.9"), BigDecimal("2.6"), null))               // aviso desligado
        assertNull(DestinationAdvisor.pickupWarning(null, BigDecimal("2.6"), BigDecimal("4.0")))               // sem busca na tela
        assertEquals(BigDecimal("4.0"), DestinationSettings().maxPickupKm)                                    // padrão
    }
}
