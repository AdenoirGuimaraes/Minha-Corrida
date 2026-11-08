package br.com.minhacorrida.core.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Uma leitura de tela (ou um toque) guardada para diagnóstico.
 *
 * @property source de onde veio (janelas, alvo da leitura ou "toque na Uber/inDrive").
 * @property result o que o app concluiu (oferta, recusa, motivo de não ter calculado).
 */
data class DiagnosticEntry(
    val timestampMillis: Long,
    val packageName: String,
    val source: String,
    val result: String,
    val texts: List<String>,
    val ocrLines: List<String> = emptyList(),
)

/**
 * Decide quais leituras guardar para que 200 entradas cubram uma corrida inteira.
 * A navegação muda números a cada segundo ("50 m", "2,5 km"): a mesma tela só com números
 * diferentes é guardada no máximo a cada [sameScreenIntervalMs]. Telas novas (textos
 * diferentes) entram com no mínimo [minIntervalMs] de espaço. Toques sempre entram.
 */
class DiagnosticsSamplingPolicy(
    private val minIntervalMs: Long = 1_000L,
    private val sameScreenIntervalMs: Long = 30_000L,
) {
    private var lastExactKey: Int? = null
    private var lastScreenKey: Int? = null
    private var lastWriteMs = 0L

    /** @return true se a leitura deve ser guardada (e registra que foi). */
    @Synchronized
    fun shouldKeep(entry: DiagnosticEntry, force: Boolean): Boolean {
        val exact = (listOf(entry.packageName, entry.result) + entry.texts + entry.ocrLines).hashCode()
        val screen = screenKey(entry)
        val now = entry.timestampMillis
        val keep = force || when {
            exact == lastExactKey -> false
            now - lastWriteMs < minIntervalMs -> false
            screen == lastScreenKey -> now - lastWriteMs >= sameScreenIntervalMs
            else -> true
        }
        if (keep) {
            lastExactKey = exact
            lastScreenKey = screen
            lastWriteMs = now
        }
        return keep
    }

    @Synchronized
    fun reset() {
        lastExactKey = null
        lastScreenKey = null
        lastWriteMs = 0L
    }

    companion object {
        private val DIGITS = Regex("""\d+""")

        /** Chave da "tela": pacote, resultado e textos sem os números. */
        fun screenKey(entry: DiagnosticEntry): Int =
            (listOf(entry.packageName, entry.result) + entry.texts + entry.ocrLines)
                .map { it.replace(DIGITS, "#") }
                .hashCode()
    }
}

/** Cabeçalho do relatório: versão, aparelho e regras do motorista no momento. */
data class DiagnosticsHeader(
    val appVersion: String,
    val device: String,
    val rules: String,
    val generatedAtMillis: Long,
)

/**
 * Relatório em Markdown para colar/anexar no chat de calibração: leituras da mais antiga
 * para a mais recente (a ordem da corrida), cada uma com os textos em bloco de código.
 */
class DiagnosticsReportFormatter(private val zone: ZoneId = ZoneId.systemDefault()) {

    fun markdown(header: DiagnosticsHeader, entries: List<DiagnosticEntry>): String = buildString {
        val ordered = entries.sortedBy { it.timestampMillis }
        appendLine("# Minha Corrida — relatório de diagnóstico")
        appendLine()
        appendLine("- Versão: ${header.appVersion}")
        appendLine("- Gerado em: ${FULL.format(at(header.generatedAtMillis))}")
        appendLine("- Aparelho: ${header.device}")
        appendLine("- Regras: ${header.rules}")
        if (ordered.isEmpty()) {
            appendLine("- Leituras: nenhuma")
            return@buildString
        }
        appendLine(
            "- Leituras: ${ordered.size} (de ${FULL.format(at(ordered.first().timestampMillis))} " +
                "a ${FULL.format(at(ordered.last().timestampMillis))}), da mais antiga para a mais recente",
        )
        appendLine("- Toques: ${ordered.count { it.source.startsWith(TAP_PREFIX) }}")
        ordered.forEachIndexed { index, entry ->
            appendLine()
            appendLine("## ${index + 1}. ${TIME.format(at(entry.timestampMillis))} · ${appName(entry.packageName)} · ${entry.result.oneLine()}")
            appendLine()
            appendLine("- Origem: ${entry.source.oneLine()}")
            appendLine("- Pacote: `${entry.packageName}`")
            block("Acessibilidade", entry.texts)
            if (entry.ocrLines.isNotEmpty()) block("OCR", entry.ocrLines)
        }
    }

    private fun StringBuilder.block(title: String, lines: List<String>) {
        appendLine()
        appendLine("$title (${lines.size}):")
        appendLine("```text")
        // Crases triplas dentro do texto quebrariam o bloco.
        lines.forEach { appendLine(it.oneLine().replace("```", "'''")) }
        appendLine("```")
    }

    private fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone)
    private fun String.oneLine() = replace('\n', ' ').trim()

    private fun appName(pkg: String) = when (pkg) {
        "com.ubercab.driver" -> "Uber"
        "sinet.startup.inDriver" -> "inDrive"
        else -> pkg
    }

    companion object {
        /** Origem dos toques do motorista ("toque na Uber", "toque no inDrive"). */
        const val TAP_PREFIX = "toque"
        private val FULL = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
