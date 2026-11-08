package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.diagnostics.DiagnosticEntry
import br.com.minhacorrida.core.diagnostics.DiagnosticsSamplingPolicy
import br.com.minhacorrida.core.port.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Últimas [MAX_ENTRIES] leituras de tela e toques nos apps de corrida, para calibrar a leitura.
 *
 * Fica num arquivo (uma leitura JSON por linha) e não no SharedPreferences: com 200 leituras
 * regravar tudo a cada segundo pesaria. Cada leitura nova é só acrescentada ao fim; quando passa
 * de [TRIM_AT], o arquivo é reescrito com as 200 mais recentes. A escrita roda numa thread
 * própria: não atrasa o card. O que guardar decide a [DiagnosticsSamplingPolicy].
 */
class DiagnosticsStore(context: Context, private val logger: AppLogger) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val io = Executors.newSingleThreadExecutor()
    private val sampling = DiagnosticsSamplingPolicy()
    private val lock = Any()
    private var cache: MutableList<DiagnosticEntry>? = null

    init {
        // Versões até a 0.2.30 guardavam 8 leituras no SharedPreferences: não servem mais.
        runCatching { context.applicationContext.deleteSharedPreferences(OLD_PREFS_NAME) }
    }

    /**
     * @param source de onde veio a leitura (janela ativa, tipo de janela, "toque na Uber" etc.).
     * @param result resumo do cálculo ou o motivo de não ter calculado.
     * @param force toques do motorista: sempre guardados, sem o limite de 1 por segundo.
     */
    fun record(
        packageName: String,
        source: String,
        texts: List<String>,
        ocrLines: List<String>,
        result: String,
        force: Boolean = false,
    ) {
        try {
            val entry = DiagnosticEntry(
                timestampMillis = System.currentTimeMillis(),
                packageName = packageName,
                source = source,
                result = result,
                texts = texts.take(MAX_LINES),
                ocrLines = ocrLines.take(MAX_LINES),
            )
            if (!sampling.shouldKeep(entry, force)) return
            io.execute { append(entry) }
        } catch (e: Exception) {
            logger.warn(TAG, "Falha ao gravar diagnóstico", e)
        }
    }

    /** Todas as leituras guardadas, da mais antiga para a mais recente. */
    fun entries(): List<DiagnosticEntry> = synchronized(lock) { loaded().toList() }

    fun clear() {
        sampling.reset()
        io.execute {
            synchronized(lock) {
                cache = mutableListOf()
                runCatching { file.delete() }.onFailure { logger.warn(TAG, "Falha ao apagar diagnóstico", it) }
            }
        }
    }

    private fun append(entry: DiagnosticEntry) {
        synchronized(lock) {
            try {
                val list = loaded().apply { add(entry) }
                if (list.size > TRIM_AT) {
                    val recent = list.takeLast(MAX_ENTRIES)
                    list.clear()
                    list.addAll(recent)
                    file.writeText(recent.joinToString("\n", postfix = "\n") { toJson(it).toString() })
                } else {
                    file.appendText(toJson(entry).toString() + "\n")
                }
            } catch (e: Exception) {
                logger.warn(TAG, "Falha ao escrever diagnóstico", e)
            }
        }
    }

    /** Lê o arquivo na primeira vez; linhas corrompidas são puladas. Chamar com [lock]. */
    private fun loaded(): MutableList<DiagnosticEntry> {
        cache?.let { return it }
        val list = mutableListOf<DiagnosticEntry>()
        if (file.exists()) {
            try {
                file.forEachLine { line ->
                    if (line.isNotBlank()) runCatching { list.add(fromJson(JSONObject(line))) }
                }
            } catch (e: Exception) {
                logger.warn(TAG, "Diagnóstico ilegível; recomeçando", e)
                list.clear()
            }
        }
        return list.takeLast(MAX_ENTRIES).toMutableList().also { cache = it }
    }

    private fun toJson(e: DiagnosticEntry) = JSONObject()
        .put("t", e.timestampMillis)
        .put("p", e.packageName)
        .put("s", e.source)
        .put("r", e.result)
        .put("x", JSONArray(e.texts))
        .put("o", JSONArray(e.ocrLines))

    private fun fromJson(o: JSONObject): DiagnosticEntry {
        fun list(key: String): List<String> {
            val array = o.optJSONArray(key) ?: return emptyList()
            return List(array.length()) { array.optString(it) }
        }
        return DiagnosticEntry(
            timestampMillis = o.getLong("t"),
            packageName = o.optString("p"),
            source = o.optString("s"),
            result = o.optString("r"),
            texts = list("x"),
            ocrLines = list("o"),
        )
    }

    companion object {
        private const val TAG = "DiagnosticsStore"
        private const val FILE_NAME = "diagnostico.jsonl"
        private const val OLD_PREFS_NAME = "minha_corrida_diagnostics_v2"
        const val MAX_ENTRIES = 200
        private const val TRIM_AT = 240
        private const val MAX_LINES = 80
    }
}
