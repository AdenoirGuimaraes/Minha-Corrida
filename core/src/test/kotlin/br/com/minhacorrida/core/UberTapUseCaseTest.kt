package br.com.minhacorrida.core

import br.com.minhacorrida.core.history.InMemoryRideHistoryRepository
import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.usecase.RideRules
import br.com.minhacorrida.core.usecase.UberTap
import br.com.minhacorrida.core.usecase.UberTapUseCase
import br.com.minhacorrida.core.usecase.UberTapUseCase.Kind
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Pedido de 08/11: Uber guarda no "Aceitar" e no "Tenho interesse"; o X não guarda. */
class UberTapUseCaseTest {
    private val repo = InMemoryRideHistoryRepository()
    private val settings = Fixtures.FakeSettings(min = BigDecimal("2.00"))
    private val tap = UberTapUseCase(RecordRideUseCase(repo, settings, NoOpLogger), NoOpLogger)
    private val evaluate = EvaluateRideUseCase(CalculateRideMetricsUseCase())

    // Prints de 08/11 17:18: R$ 8,56 em 1,8 + 2,3 km (verde) e R$ 6,03 em 3,5 + 2,6 km (vermelha).
    private val verde = evaluate(
        RideOffer(Platform.UBER, BigDecimal("8.56"), tripKm = BigDecimal("2.3"), pickupKm = BigDecimal("1.8")),
        RideRules(BigDecimal("2.00")),
    )
    private val vermelha = evaluate(
        RideOffer(Platform.UBER, BigDecimal("6.03"), tripKm = BigDecimal("2.6"), pickupKm = BigDecimal("3.5")),
        RideRules(BigDecimal("2.00")),
    )

    @Test
    fun `reconhece os botoes do cartao`() {
        assertEquals(Kind.ACCEPT, tap.classify("Aceitar"))
        assertEquals(Kind.ACCEPT, tap.classify("ACEITAR "))
        assertEquals(Kind.ACCEPT, tap.classify("Tenho interesse"))
        assertEquals(Kind.REJECT, tap.classify("Fechar"))
        assertEquals(Kind.REJECT, tap.classify("Recusar"))
        assertEquals(Kind.REJECT, tap.classify("Não tenho interesse"))
        assertEquals(Kind.OTHER, tap.classify(""))
        assertEquals(Kind.OTHER, tap.classify("Mapa"))
        assertEquals(Kind.OTHER, tap.classify("Aceitar corridas automaticamente em todos os horários do dia"))
    }

    @Test
    fun `aceitar guarda a corrida do cartao`() {
        val result = assertIs<UberTap.Accepted>(tap.onTap("Aceitar", verde))
        val saved = assertNotNull(result.saved)
        assertEquals(Platform.UBER, saved.platform)
        assertFalse(saved.done) // aceitar não é concluir: o motorista marca depois
    }

    @Test
    fun `aceite de corrida vermelha tambem e guardado (decisao do motorista)`() {
        val saved = assertNotNull(assertIs<UberTap.Accepted>(tap.onTap("Tenho interesse", vermelha)).saved)
        assertFalse(saved.good)
        assertEquals(1, repo.between(0, Long.MAX_VALUE).size)
    }

    @Test
    fun `X nao guarda nada`() {
        assertIs<UberTap.Rejected>(tap.onTap("Fechar", verde))
        assertEquals(0, repo.between(0, Long.MAX_VALUE).size)
    }

    @Test
    fun `aceite repetido nao duplica e aceite sem cartao e ignorado`() {
        tap.onTap("Aceitar", verde)
        assertNull(assertIs<UberTap.Accepted>(tap.onTap("Aceitar", verde)).saved)
        assertIs<UberTap.Ignored>(tap.onTap("Aceitar", null))
        assertEquals(1, repo.between(0, Long.MAX_VALUE).size)
    }
}
