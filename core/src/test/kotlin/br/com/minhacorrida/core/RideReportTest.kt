package br.com.minhacorrida.core

import br.com.minhacorrida.core.goal.GoalSettingsRepository
import br.com.minhacorrida.core.goal.ProfitDeduction
import br.com.minhacorrida.core.history.DateRange
import br.com.minhacorrida.core.history.InMemoryRideHistoryRepository
import br.com.minhacorrida.core.history.RideHistoryUseCase
import br.com.minhacorrida.core.km.FuelEntry
import br.com.minhacorrida.core.km.FuelLogRepository
import br.com.minhacorrida.core.km.KmLogRepository
import br.com.minhacorrida.core.km.KmSettingsRepository
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.report.RideReportUseCase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pedido de 11/11: relatório .md do período para mandar a uma IA analisar. */
class RideReportTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val day = LocalDate.of(2026, 11, 10)
    private val repo = InMemoryRideHistoryRepository()
    private val settings = Fixtures.FakeSettings(
        min = BigDecimal("2.00"),
        cost = OperatingCost(BigDecimal("6.30"), BigDecimal("10.5"), BigDecimal("0.25")),
        inDriveFee = BigDecimal("10"),
    )
    private val goals = object : GoalSettingsRepository {
        override fun getDailyGoal() = BigDecimal("70")
        override fun setDailyGoal(value: BigDecimal?) = Unit
        override fun getProfitDeductions() = listOf(ProfitDeduction("Dízimo", BigDecimal("10")))
        override fun setProfitDeductions(deductions: List<ProfitDeduction>) = Unit
    }
    private val kmSettings = object : KmSettingsRepository {
        override fun isKmTrackingEnabled() = false
        override fun setKmTrackingEnabled(enabled: Boolean) = Unit
    }
    private val kmLog = object : KmLogRepository {
        override fun addMeters(date: LocalDate, meters: Double) = Unit
        override fun kmBetween(start: LocalDate, endExclusive: LocalDate) = BigDecimal.ZERO
    }
    private val fuel = object : FuelLogRepository {
        override fun all() = listOf(FuelEntry("f1", day, BigDecimal("45230"), BigDecimal("38.5"), BigDecimal("242.50")))
        override fun save(entry: FuelEntry) = Unit
        override fun delete(id: String) = Unit
    }
    private val report = RideReportUseCase(repo, settings, goals, kmSettings, kmLog, fuel, NoOpLogger, zone)

    @Test
    fun `relatorio traz configuracoes, resumo, por app, por dia, corridas e abastecimentos`() {
        val history = RideHistoryUseCase(repo, settings, NoOpLogger, zone, today = { day })
        val at = day.atTime(20, 6).atZone(zone).toInstant().toEpochMilli()
        // Corrida do inDrive de 10/11: R$ 12 (era 8), 1,6 + 1,4 km; e uma Uber não realizada.
        history.registerManual(BigDecimal("12"), BigDecimal("1.6"), BigDecimal("1.4"), at, Platform.INDRIVE)
        history.registerManual(BigDecimal("9.40"), BigDecimal("1.8"), BigDecimal("4.2"), at + 3_600_000, Platform.UBER)
        val uber = repo.between(0, Long.MAX_VALUE).first { it.platform == Platform.UBER }
        history.setDone(uber.id, false)

        val md = report.markdown(DateRange(day.minusDays(6), day.plusDays(1)), "0.2.43 (43)", at)
        listOf(
            "# Minha Corrida — relatório de corridas",
            "- Período: 04/11/2026 a 10/11/2026 (7 dias)",
            "| Mínimo R$/km (vermelho abaixo) | 2,00 |",
            "| Deduções do lucro | Dízimo 10% |",
            "| Corridas realizadas | 1 em 1 dia(s) |",
            "| **Recebido (líquido)** | **10,80** |",          // 12 − 10%
            "| Gasolina das corridas | 1,80 |",                // 3 km × 0,60
            "| Lucro das corridas (recebido − gasolina − manutenção) | 8,25 |",
            "| Dízimo (10%) | 0,83 |",
            "| **Fica com você** | **7,42** |",
            "| Meta diária batida | 0 de 1 dia(s) trabalhado(s) |",
            "| inDrive | 1 | 3 |",
            "## Corridas NÃO marcadas como realizadas (fora das contas) (1)",
            "| 10/11/2026 | 45.230 | 38,5 | 242,50 | 6,30 | sim |",
            "## Como as contas são feitas",
        ).forEach { assertTrue(md.contains(it), "faltou: $it\n\n$md") }
    }
}
