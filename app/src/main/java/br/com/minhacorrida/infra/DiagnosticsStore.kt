package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.port.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda a última leitura de tela de cada app de corrida para diagnóstico
 * (ex.: descobrir se a Uber expõe o texto do cartão de oferta).
 * Gravação limitada a uma por segundo e só quando o conteúdo muda.
 */
class DiagnosticsStore(context: Context, private val logger: AppLogger) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lastHashByPackage = mutableMapOf<String, Int>()
    private var lastWriteMs = 0L

    /**
     * @param windowPackages pacotes das janelas visíveis no momento da leitura.
     * @param result resumo do cálculo ou o motivo de não ter calculado.
     */
    fun record(
        packageName: String,
        windowPackages: List<String>,
        texts: List<String>,
        ocrLines: List<String>,
        result: String,
    ) {
        try {
            val hash = (texts + ocrLines + result).hashCode()
            val now = System.currentTimeMillis()
            if (lastHashByPackage[packageName] == hash || now - lastWriteMs < MIN_INTERVAL_MS) return
            lastHashByPackage[packageName] = hash
            lastWriteMs = now

            val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR")).format(Date(now))
            val snapshot = buildString {
                appendLine("== $packageName às $time ==")
                appendLine("Resultado: $result")
                appendLine("Janelas: ${windowPackages.joinToString()}")
                appendLine("Textos da acessibilidade (${texts.size}):")
                texts.take(MAX_LINES).forEach { appendLine("  • ${it.replace('\n', ' ')}") }
                if (ocrLines.isNotEmpty()) {
                    appendLine("Textos do OCR (${ocrLines.size}):")
                    ocrLines.take(MAX_LINES).forEach { appendLine("  • $it") }
                }
            }
            prefs.edit().putString(KEY_PREFIX + packageName, snapshot).apply()
        } catch (e: Exception) {
            logger.warn(TAG, "Falha ao gravar diagnóstico", e)
        }
    }

    /** Todas as últimas leituras, prontas para exibir ou compartilhar. */
    fun dump(): String =
        prefs.all.filterKeys { it.startsWith(KEY_PREFIX) }
            .values.filterIsInstance<String>()
            .joinToString("\n")
            .ifBlank { "Nenhuma leitura registrada ainda. Abra uma oferta no Uber ou inDrive e volte aqui." }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val TAG = "DiagnosticsStore"
        const val PREFS_NAME = "minha_corrida_diagnostics"
        const val KEY_PREFIX = "last_"
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_LINES = 80
    }
}
