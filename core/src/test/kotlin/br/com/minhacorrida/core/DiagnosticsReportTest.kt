package br.com.minhacorrida.core

import br.com.minhacorrida.core.diagnostics.DiagnosticEntry
import br.com.minhacorrida.core.diagnostics.DiagnosticsHeader
import br.com.minhacorrida.core.diagnostics.DiagnosticsReportFormatter
import br.com.minhacorrida.core.diagnostics.DiagnosticsSamplingPolicy
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsReportTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val t0 = 1_794_000_000_000L

    private fun entry(at: Long, texts: List<String>, result: String = "nenhuma oferta reconhecida", source: String = "tela") =
        DiagnosticEntry(at, "com.ubercab.driver", source, result, texts)

    @Test
    fun `navegacao que so muda numeros entra no maximo a cada 30 s`() {
        val policy = DiagnosticsSamplingPolicy()
        assertTrue(policy.shouldKeep(entry(t0, listOf("50 m", "Coletar pagamento")), force = false))
        assertFalse(policy.shouldKeep(entry(t0 + 2_000, listOf("40 m", "Coletar pagamento")), force = false))
        assertTrue(policy.shouldKeep(entry(t0 + 31_000, listOf("10 m", "Coletar pagamento")), force = false))
    }

    @Test
    fun `tela nova entra logo e toque sempre entra`() {
        val policy = DiagnosticsSamplingPolicy()
        assertTrue(policy.shouldKeep(entry(t0, listOf("Coletar pagamento")), force = false))
        assertTrue(policy.shouldKeep(entry(t0 + 1_500, listOf("Viagem concluída")), force = false))
        assertFalse(policy.shouldKeep(entry(t0 + 1_600, listOf("Avaliar passageiro")), force = false)) // < 1 s
        assertTrue(policy.shouldKeep(entry(t0 + 1_700, listOf("Aceitar"), source = "toque na Uber"), force = true))
    }

    @Test
    fun `relatorio em markdown fica em ordem da corrida e com blocos de codigo`() {
        val header = DiagnosticsHeader("0.2.31 (31)", "Samsung SM-A, Android 14", "mínimo R$ 2/km", t0)
        val md = DiagnosticsReportFormatter(zone).markdown(
            header,
            listOf(
                entry(t0 + 5_000, listOf("Coletar pagamento")),
                entry(t0, listOf("Aceitar"), result = "aceite: R$ 2,09/km", source = "toque na Uber"),
            ),
        )
        assertTrue(md.startsWith("# Minha Corrida — relatório de diagnóstico"))
        assertTrue(md.contains("- Versão: 0.2.31 (31)"))
        assertTrue(md.contains("- Toques: 1"))
        assertTrue(md.indexOf("aceite: R$ 2,09/km") < md.indexOf("Coletar pagamento"))
        assertTrue(md.contains("## 1. ") && md.contains("· Uber · aceite"))
        assertTrue(md.contains("```text\nAceitar\n```"))
    }
}
