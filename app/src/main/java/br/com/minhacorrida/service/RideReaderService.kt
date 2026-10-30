package br.com.minhacorrida.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.overlay.OverlayVisibilityPolicy
import br.com.minhacorrida.di.AppContainer

/**
 * Serviço de acessibilidade: escuta mudanças de tela dos apps de corrida,
 * extrai os textos (e, quando preciso, lê o mapa com OCR), calcula o R$/km
 * e mostra o leitor.
 */
class RideReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val visibility = OverlayVisibilityPolicy()
    private lateinit var container: AppContainer
    private lateinit var extractor: ScreenTextExtractor
    private lateinit var ocrReader: ScreenOcrReader
    private lateinit var overlay: RideOverlayController

    private var pendingPackage: String? = null
    private var lastAnalysis: RideAnalysis? = null
    private val analyzeTask = Runnable { pendingPackage?.let(::analyzeScreen) }

    // Estado da leitura visual (OCR) da oferta atual.
    private var ocrOfferKey: String? = null
    private var ocrTexts: List<String> = emptyList()
    private var ocrAttempts = 0
    private var ocrInFlight = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            container = (application as MinhaCorridaApp).container
            extractor = ScreenTextExtractor(container.logger)
            ocrReader = ScreenOcrReader(this, container.logger)
            overlay = RideOverlayController(this, container.formatter, container.logger) { dismissed ->
                visibility.onDismissed(dismissed.offer)
            }
            container.logger.debug(TAG, "Serviço conectado")
        } catch (e: Exception) {
            android.util.Log.e("MC/$TAG", "Falha ao iniciar o serviço", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlay.isInitialized) return
        val pkg = event?.packageName?.toString() ?: return

        if (!container.parserRegistry.supports(pkg)) {
            handleForeignPackage(event, pkg)
            return
        }

        // Debounce: a tela dispara dezenas de eventos por segundo; analisamos só o último.
        pendingPackage = pkg
        handler.removeCallbacks(analyzeTask)
        handler.postDelayed(analyzeTask, DEBOUNCE_MS)
    }

    /** Esconde o leitor quando o motorista troca para outro app. */
    private fun handleForeignPackage(event: AccessibilityEvent, pkg: String) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || pkg == packageName) return
        val activePkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (activePkg == pkg) {
            lastAnalysis = null
            overlay.scheduleHide()
        }
    }

    private fun analyzeScreen(pkg: String) {
        try {
            val root = findRoot(pkg) ?: return overlay.scheduleHide()
            val screenTexts = extractor.extract(root)
            val texts = screenTexts.map { it.text }

            // Nova oferta na tela: descarta o OCR da anterior.
            val offerKey = offerKeyOf(texts)
            if (offerKey != ocrOfferKey) {
                ocrOfferKey = offerKey
                ocrTexts = emptyList()
                ocrAttempts = 0
            }
            val combined = texts + ocrTexts

            if (container.analyzeScreen.needsVisualReading(pkg, combined)) {
                recordDiagnostics(pkg, texts, "aguardando leitura do mapa (tentativa ${ocrAttempts + 1})")
                requestMapReading(offerKey, mapBottomOf(screenTexts))
                return
            }

            val analysis = container.analyzeScreen.analyze(pkg, combined)
            recordDiagnostics(pkg, texts, analysis?.let { container.formatter.headline(it) } ?: "nenhuma oferta reconhecida")
            if (analysis == null) {
                lastAnalysis = null
                overlay.scheduleHide()
                return
            }
            if (analysis != lastAnalysis) {
                lastAnalysis = analysis
                container.logger.debug(TAG, "Oferta: $analysis")
            }
            // Oferta já fechada (toque ou tempo) não reabre até surgir outra.
            if (visibility.shouldShow(analysis.offer)) {
                overlay.show(analysis, container.settings.getOverlayTimeoutSeconds())
            }
        } catch (e: Exception) {
            container.logger.error(TAG, "Erro ao analisar a tela de $pkg", e)
        }
    }

    /**
     * Lê o mapa por OCR. O leitor é escondido antes para não aparecer no print,
     * e há algumas tentativas porque o mapa pode ainda estar desenhando as bolhas.
     */
    private fun requestMapReading(offerKey: String, mapBottomPx: Int) {
        if (ocrInFlight || ocrAttempts >= MAX_OCR_ATTEMPTS || !ocrReader.isSupported) return
        ocrInFlight = true
        ocrAttempts++
        overlay.hideNow()

        val delay = if (ocrAttempts == 1) MAP_SETTLE_MS else OCR_RETRY_MS
        handler.postDelayed({
            ocrReader.read(mapBottomPx) { lines ->
                ocrInFlight = false
                if (offerKey == ocrOfferKey) {
                    ocrTexts = lines
                    handler.removeCallbacks(analyzeTask)
                    handler.post(analyzeTask)
                }
            }
        }, delay)
    }

    /** Identifica a oferta pelos textos de valor e distância do cartão. */
    private fun offerKeyOf(texts: List<String>): String =
        texts.filter { it.contains("R$") || it.contains("~") }.joinToString("|")

    /** O mapa termina onde começa o cartão da oferta ("R$ x/km ~y km"). */
    private fun mapBottomOf(screenTexts: List<ScreenText>): Int {
        val cardTop = screenTexts
            .firstOrNull { it.text.contains("~") && it.text.contains("km") }
            ?.topPx
        val fallback = (resources.displayMetrics.heightPixels * MAP_FALLBACK_RATIO).toInt()
        return cardTop?.takeIf { it > 0 } ?: fallback
    }

    private fun recordDiagnostics(pkg: String, texts: List<String>, result: String) {
        val windowPackages = runCatching {
            windows.mapNotNull { it.root?.packageName?.toString() }
        }.getOrDefault(emptyList())
        container.diagnostics.record(pkg, windowPackages, texts, ocrTexts, result)
    }

    /** A oferta pode estar numa janela que não é a ativa (ex.: cartão flutuante da Uber). */
    private fun findRoot(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }?.let { return it }
        return windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName?.toString() == pkg }
        }
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hideNow()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hideNow()
        if (::ocrReader.isInitialized) ocrReader.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "RideReaderService"
        const val DEBOUNCE_MS = 120L
        const val MAP_SETTLE_MS = 300L
        const val OCR_RETRY_MS = 500L
        const val MAX_OCR_ATTEMPTS = 3
        const val MAP_FALLBACK_RATIO = 0.4
    }
}
