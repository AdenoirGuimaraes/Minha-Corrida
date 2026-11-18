package br.com.minhacorrida.core

import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Place
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.neighborhood.BrazilianStates
import br.com.minhacorrida.core.neighborhood.CityChoice
import br.com.minhacorrida.core.neighborhood.Neighborhood
import br.com.minhacorrida.core.neighborhood.NeighborhoodMark
import br.com.minhacorrida.core.neighborhood.NeighborhoodPolicy
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pedido de 17/11: bairros marcados (bom/longe/não vou) e o painel novo do card. */
class NeighborhoodTest {
    private val center = GeoPoint(-27.8157, -50.3264, 0f, 0)
    // ~1,11 km por 0,01° de latitude.
    private fun south(steps: Double) = GeoPoint(center.latitude - 0.01 * steps, center.longitude, 0f, 0)

    private val lages = CityChoice("SC", "Lages")
    private val bairros = listOf(
        Neighborhood("Centro", center, NeighborhoodMark.GOOD),
        Neighborhood("Universitário", south(1.0), NeighborhoodMark.GOOD),
        Neighborhood("Guarujá", south(5.0), NeighborhoodMark.FAR),       // 4 × 1,11 km do Universitário
        Neighborhood("Bairro Evitado", south(2.0), NeighborhoodMark.AVOID),
        Neighborhood("Caravágio", south(1.5)),
    )
    private val policy = NeighborhoodPolicy(lages, bairros, center)

    @Test
    fun `classifica o destino e calcula a volta ate o bairro bom mais perto`() {
        val far = policy.classify(Place("x", "guaruja", "Lages"))!!          // sem acento também acha
        assertEquals(NeighborhoodMark.FAR, far.mark)
        assertEquals("Guarujá", far.neighborhood)
        assertEquals("5.8", far.returnKm!!.toPlainString())                 // 4,45 km × 1,3 até o Universitário
        assertEquals(NeighborhoodMark.AVOID, policy.classify(Place("x", "Bairro Evitado", "Lages"))!!.mark)
        assertNull(policy.classify(Place("x", "Caravágio", "Lages"))!!.mark) // sem marcação = neutro
        val outside = policy.classify(Place("x", "Centro", "Correia Pinto"))!!
        assertEquals("Correia Pinto", outside.outsideCity)
        assertEquals(NeighborhoodMark.FAR, outside.mark)
        assertNull(policy.classify(Place("x", null, "Lages")))
    }

    private val cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.30"))  // 0,90/km
    private val rules = RideRules(
        BigDecimal("2.00"), operatingCost = cost, minNetProfit = BigDecimal("6.00"),
        destinations = policy, maxPickupKm = BigDecimal("4.0"),
    )
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())

    private fun offer(fare: String, pickup: String, trip: String, bairro: String) = RideOffer(
        Platform.UBER, BigDecimal(fare), tripKm = BigDecimal(trip), pickupKm = BigDecimal(pickup),
        destination = Place("Rua X, $bairro, Lages", bairro, "Lages"),
    )

    @Test
    fun `bairro nao vou deixa o card vermelho`() {
        val analysis = evaluate(offer("20.00", "1.0", "3.0", "Bairro Evitado"), rules)
        assertEquals(RideRating.BELOW_MINIMUM, analysis.rating)
        val panel = RideAnalysisFormatter().panel(analysis)
        assertEquals("NÃO VOU", panel.pill)
        assertEquals("Bairro marcado como “não vou”: recusar.", panel.note)
    }

    @Test
    fun `bairro longe soma a volta no Min verde e mostra a volta no painel`() {
        val analysis = evaluate(offer("17.08", "4.6", "3.3", "Guarujá"), rules)
        // Gasto 7,9 × 0,90 = 7,11; Mín. verde sem volta = 2,00 × 7,9 = 15,80; volta 5,8 × 0,90 = 5,22.
        assertEquals("5.22", analysis.minimum.returnCost!!.toPlainString())
        assertEquals("21.02", analysis.minimum.greenFare.toPlainString())
        assertTrue(analysis.longPickup)
        val card = RideAnalysisFormatter().card(analysis)
        assertEquals("Pelo R$ 2,00/km\n+ volta R$ 5,22\nLucro R$ 8,69", card.minimumLabel)  // 21,02 − 7,11 − 5,22
        val panel = card.panel!!
        assertEquals("LONGE", panel.pill)
        assertEquals("Guarujá", panel.destination)
        assertEquals("Busca longa: 4,6 km · viagem 3,3 km", panel.pickup)
        assertEquals("≈ 5,8 km · R$ 5,22\nLucro com a volta: R$ 4,75", panel.returnLine)   // 9,97 − 5,22
    }

    @Test
    fun `bairro bom e neutro nao mexem no Min verde`() {
        val good = evaluate(offer("14.00", "1.2", "3.4", "Universitário"), rules)
        assertNull(good.minimum.returnCost)
        assertEquals("BOM", RideAnalysisFormatter().panel(good).pill)
        assertEquals("1,2 km · viagem 3,4 km", RideAnalysisFormatter().panel(good).pickup)
        val neutral = RideAnalysisFormatter().panel(evaluate(offer("14.00", "1.2", "3.4", "Caravágio"), rules))
        assertNull(neutral.pill)
        assertEquals("Caravágio", neutral.destination)
    }

    @Test
    fun `municipio pelas primeiras letras`() {
        assertEquals(listOf("Laguna", "Lages"), BrazilianStates.filterCities(listOf("Laguna", "Lages", "Joinville"), "lag"))
        assertEquals(27, BrazilianStates.ALL.size)
    }
}
