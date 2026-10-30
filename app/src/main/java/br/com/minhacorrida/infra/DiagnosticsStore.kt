package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Histórico das últimas leituras de tela dos apps de corrida, para diagnóstico.
 * Guarda as [MAX_ENTRIES] leituras mais recentes (de qualquer app), uma por segundo
 * no máximo e só quando o conteúdo muda — assim uma leitura vazia não apaga a útil.
 */
class DiagnosticsStore(context: Context, private val logger: AppLogger) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var lastHash = 0
    private var lastWriteMs = 0L

    /**
     * @param source de onde veio a leitura (janela ativa, tipo de janela etc.).
     * @param result resumo do cálculo ou o motivo de não ter calculado.
     */
    fun record(
        packageName: String,
        source: String,
        texts: List<String>,
        ocrLines: List<String>,
        result: String,
    ) {
        try {
            val hash = (listOf(packageName, result) + texts + ocrLines).hashCode()
            val now = System.currentTimeMillis()
            if (hash == lastHash || now - lastWriteMs < MIN_INTERVAL_MS) return
            lastHash = hash
            lastWriteMs = now

            val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR")).format(Date(now))
            val snapshot = buildString {
                appendLine("== $packageName às $time ==")
                appendLine("Resultado: $result")
                appendLine("Origem: $source")
                appendLine("Textos da acessibilidade (${texts.size}):")
                texts.take(MAX_LINES).forEach { appendLine("  • ${it.replace('\n', ' ')}") }
                if (ocrLines.isNotEmpty()) {
                    appendLine("Textos do OCR (${ocrLines.size}):")
                    ocrLines.take(MAX_LINES).forEach { appendLine("  • ${it.replace('\n', ' ')}") }
                }
            }

            val history = loadHistory().apply { add(snapshot) }
            val trimmed = history.takeLast(MAX_ENTRIES)
            prefs.edit().putString(KEY_HISTORY, JSONArray(trimmed).toString()).apply()
        } catch (e: Exception) {
            logger.warn(TAG, "Falha ao gravar diagnóstico", e)
        }
    }

    /** Leituras da mais recente para a mais antiga, prontas para exibir ou compartilhar. */
    fun dump(): String =
        loadHistory().asReversed().joinToString("\n")
            .ifBlank { "Nenhuma leitura registrada ainda. Abra uma oferta no Uber ou inDrive e volte aqui." }

    fun clear() {
        lastHash = 0
        prefs.edit().clear().apply()
    }

    private fun loadHistory(): MutableList<String> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            logger.warn(TAG, "Histórico de diagnóstico corrompido; recomeçando", e)
            mutableListOf()
        }
    }

    private companion object {
        const val TAG = "DiagnosticsStore"
        const val PREFS_NAME = "minha_corrida_diagnostics_v2"
        const val KEY_HISTORY = "history"
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_ENTRIES = 8
        const val MAX_LINES = 60
    }
}
