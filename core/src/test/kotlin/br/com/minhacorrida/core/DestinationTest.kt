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

    private val settings = DestinationSettings()
    private val cost = BigDecimal("0.85")

    @Test
    fun `destino longe mostra a volta vazia e o lucro com a volta`() {
        val hint = DestinationAdvisor.advise(Place("x", "Guarujá", "Lages"), BigDecimal("8.4"), cost, BigDecimal("7.91"), settings)!!
        assertEquals("⚠️ Destino longe: Guarujá\nVolta vazia ≈ 8,4 km · R$ 7,14 → lucro c/ volta R$ 0,77", hint.text)
        assertTrue(hint.warning)
        val loss = DestinationAdvisor.advise(Place("x", "Guarujá", "Lages"), BigDecimal("8.4"), cost, BigDecimal("5.00"), settings)!!
        assertTrue(loss.text.endsWith("→ prejuízo c/ volta R$ 2,14"))
    }

    @Test
    fun `fora da cidade, bairro a evitar e destino perto`() {
        assertEquals(
            "⚠️ Fora de Lages: Centro, Correia Pinto",
            DestinationAdvisor.advise(Place("x", "Centro", "Correia Pinto"), null, cost, BigDecimal("9"), settings)!!.text,
        )
        val avoid = settings.copy(avoid = listOf("guaruja"))                    // sem acento também vale
        assertEquals("⛔ Bairro a evitar: Guarujá", DestinationAdvisor.advise(Place("x", "Guarujá", "Lages"), null, cost, null, avoid)!!.text)
        val near = DestinationAdvisor.advise(Place("x", "Centro", "Lages"), BigDecimal("1.5"), cost, BigDecimal("9"), settings)!!
        assertEquals("📍 Destino: Centro", near.text)                           // volta curta: sem linha de volta
        assertFalse(near.warning)
        assertNull(DestinationAdvisor.advise(Place("x", "Centro", "Lages"), null, cost, null, settings.copy(enabled = false)))
    }

    @Test
    fun `km da volta pela linha reta vezes 1,3`() {
        val base = DestinationSettings.LAGES_CENTER
        val south = GeoPoint(base.latitude - 0.01, base.longitude, 0f, 0)      // ~1,11 km em linha reta
        assertEquals("1.4", DestinationAdvisor.returnKm(south, base).toPlainString())
    }
}
