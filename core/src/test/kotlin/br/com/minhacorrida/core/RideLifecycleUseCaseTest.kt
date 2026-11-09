package br.com.minhacorrida.core

import br.com.minhacorrida.core.history.InMemoryRideHistoryRepository
import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideLifecycleEvent
import br.com.minhacorrida.core.usecase.RideLifecycleUseCase
import br.com.minhacorrida.core.usecase.RideRules
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Textos reais dos relatórios de diagnóstico de 09/11 (inDrive 18:26–18:43, Uber 18:49–19:00). */
class RideLifecycleUseCaseTest {
    private var now = 1_794_000_000_000L
    private val repo = InMemoryRideHistoryRepository()
    private val settings = Fixtures.FakeSettings(
        min = BigDecimal("2.00"),
        cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25")),
        inDriveFee = BigDecimal("10"),
    )
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())
    private val lifecycle = RideLifecycleUseCase(
        evaluate, settings, RecordRideUseCase(repo, settings, NoOpLogger) { now }, repo, NoOpLogger,
    ) { now }

    private fun rules() = RideRules(BigDecimal("2.00"), operatingCost = settings.cost, inDriveFeePercent = settings.inDriveFee)
    private fun offer(platform: Platform, fare: String, pickup: String, trip: String) =
        evaluate(RideOffer(platform, BigDecimal(fare), tripKm = BigDecimal(trip), pickupKm = BigDecimal(pickup)), rules())

    private fun rides() = repo.between(0, Long.MAX_VALUE)

    // ---- inDrive: Jana, pedido R$ 10,50, contraproposta R$ 13 aceita ----

    private val janaPedido = offer(Platform.INDRIVE, "10.50", "1.2", "3.3")
    private val oferecendo = listOf("Oferecendo sua tarifa R$ 13. Aguarde a resposta", "Jana", "4.95", "R$ 10,50", "Preço justo")
    private val indoBuscar = listOf(
        "Navegar", "Embarque em ~3 min", "Ponto de embarque", "Rua Célio Batista de Castro 95 (Santa Helena)",
        "Jana", "4.95 (534)", "R$ 13 · Dinheiro", "Ponto de destino", "Unifacvest (Avenida Marechal Floriano - Centro, Lages - SC)", "Estou aqui",
    )
    private val esperando = listOf("Tempo de espera 04:58", "O passageiro foi notificado", "Jana", "R$ 13 · Dinheiro", "Começar a corrida")
    private val emViagem = listOf("Chegada em ~5 min", "R$ 13 · Dinheiro", "Jana", "Concluir corrida")
    private val pergunta = listOf("A viagem foi concluída?", "Jana", "R$ 13 · Dinheiro", "Sim, concluir", "Não, continuar")
    private val avaliacao = listOf("Você está R$ 13 mais perto de atingir seu plano de renda diária", "Avalie seu passageiro", "Fechar")

    @Test
    fun `inDrive grava so quando o passageiro aceita, com o valor da contraproposta, e conclui no fim`() {
        lifecycle.onOffer(janaPedido)
        assertIs<RideLifecycleEvent.CounterOfferSent>(lifecycle.onScreen(Platform.INDRIVE, oferecendo))
        assertTrue(rides().isEmpty()) // aguardando o passageiro: nada gravado

        val confirmed = assertIs<RideLifecycleEvent.Confirmed>(lifecycle.onScreen(Platform.INDRIVE, indoBuscar)).record
        assertEquals("13.00", confirmed.fare.toPlainString())
        assertEquals("10.50", confirmed.originalFare!!.toPlainString())
        assertEquals("11.70", confirmed.netFare.toPlainString()) // 13 − 10%
        assertFalse(confirmed.done)

        // Telas seguintes da mesma corrida não gravam de novo.
        assertNull(lifecycle.onScreen(Platform.INDRIVE, esperando))
        assertNull(lifecycle.onScreen(Platform.INDRIVE, emViagem))
        assertNull(lifecycle.onScreen(Platform.INDRIVE, pergunta))
        assertEquals(1, rides().size)

        assertIs<RideLifecycleEvent.Completed>(lifecycle.onScreen(Platform.INDRIVE, avaliacao))
        assertTrue(rides().single().done)
    }

    @Test
    fun `inDrive contraproposta nao aceita nao grava (Vitoria, 18h28)`() {
        lifecycle.onOffer(offer(Platform.INDRIVE, "7.50", "1.3", "3.6"))
        lifecycle.onScreen(Platform.INDRIVE, listOf("Oferecendo sua tarifa R$ 13. Aguarde a resposta", "Vitória jagelski"))
        lifecycle.onScreen(Platform.INDRIVE, listOf("Opções", "Igor", "R$ 12,50", "Pedidos de viagem"))
        assertTrue(rides().isEmpty())
    }

    @Test
    fun `inDrive vermelho aceito tambem e gravado (decisao do motorista)`() {
        lifecycle.onOffer(offer(Platform.INDRIVE, "7.00", "3.0", "2.9"))
        val saved = assertIs<RideLifecycleEvent.Confirmed>(lifecycle.onScreen(Platform.INDRIVE, indoBuscar.map {
            if (it.startsWith("R$")) "R$ 7 · PIX" else it
        })).record
        assertFalse(saved.good)
    }

    @Test
    fun `confirmacao sem pedido lido nao grava e nao insiste`() {
        assertIs<RideLifecycleEvent.ConfirmedWithoutOffer>(lifecycle.onScreen(Platform.INDRIVE, indoBuscar))
        assertNull(lifecycle.onScreen(Platform.INDRIVE, esperando))
        assertTrue(rides().isEmpty())
    }

    // ---- Uber: Fernanda, R$ 7,24 em 1,5 + 1,3 km ----

    private val fernanda = offer(Platform.UBER, "7.24", "1.5", "1.3")
    private val uberIndoBuscar = listOf(
        "60", "LIMITE", "Recursos de segurança", "Navegar", "Não é possível ficar offline", "Preferências",
        "4 min", "1,5 km", "Encontro com Fernanda", "Conteúdo", "Contato", "Fernanda", "Ícone", "Entrega", "•  UberX",
    )
    private val uberEmViagem = listOf("Destino de Fernanda", "Fernanda", "Registro de viagens")
    private val uberAvaliacao = listOf("Registro de viagens", "Como foi a viagem?", "Fernanda", "Avaliar usuário")

    @Test
    fun `Uber grava ao aparecer Encontro com e conclui na avaliacao`() {
        lifecycle.onOffer(fernanda)
        val saved = assertIs<RideLifecycleEvent.Confirmed>(lifecycle.onScreen(Platform.UBER, uberIndoBuscar)).record
        assertEquals("7.24", saved.fare.toPlainString())
        assertEquals(Platform.UBER, saved.platform)

        // Ofertas que chegam durante a corrida (R$ 9,96; R$ 12,13…) não são gravadas.
        lifecycle.onOffer(offer(Platform.UBER, "9.96", "5.7", "4.3"))
        assertNull(lifecycle.onScreen(Platform.UBER, uberIndoBuscar))
        assertNull(lifecycle.onScreen(Platform.UBER, uberEmViagem))
        assertEquals(1, rides().size)

        assertIs<RideLifecycleEvent.Completed>(lifecycle.onScreen(Platform.UBER, uberAvaliacao))
        assertTrue(rides().single().done)
    }

    @Test
    fun `Uber oferta so mostrada (sem Encontro com) nao grava`() {
        lifecycle.onOffer(offer(Platform.UBER, "12.13", "6.2", "5.9"))
        lifecycle.onScreen(Platform.UBER, listOf("Você está online", "Procurando viagens", "Missão"))
        assertTrue(rides().isEmpty())
    }

    @Test
    fun `Uber texto do OCR com lixo antes ainda confirma`() {
        lifecycle.onOffer(fernanda)
        assertIs<RideLifecycleEvent.Confirmed>(lifecycle.onScreen(Platform.UBER, listOf("Oo Encontro com Fernanda")))
    }

    @Test
    fun `pedido antigo (mais de 10 min) nao vale para a confirmacao`() {
        lifecycle.onOffer(fernanda)
        now += 11 * 60_000L
        assertIs<RideLifecycleEvent.ConfirmedWithoutOffer>(lifecycle.onScreen(Platform.UBER, uberIndoBuscar))
        assertTrue(rides().isEmpty())
    }
}
