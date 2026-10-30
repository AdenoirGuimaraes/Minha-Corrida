package br.com.minhacorrida.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.overlay.OverlayVisibilityPolicy
import br.com.minhacorrida.core.parser.VisualReadingMode
import br.com.minhacorrida.di.AppContainer

/**
 * Serviço de acessibilidade: escuta mudanças de tela dos apps de corrida,
 * extrai os textos (e, quando preciso, lê a tela com OCR), calcula o R$/km
 * e mostra o leitor.
 *
 * Só analisa o app que está em primeiro plano: janelas flutuantes de outro app
 * (ex.: o botão "Uber →" sobre o inDrive) são ignoradas e não escondem o leitor.
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

    // Estado da leitura visual (OCR).
    private var ocrOfferKey: String? = null
    private var ocrPackage: String? = null
    private var ocrTexts: List<String> = emptyList()
    private var ocrTakenAtMs = 0L
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
        if (foregroundPackage() == pkg) {
            lastAnalysis = null
            overlay.scheduleHide()
        }
    }

    private fun analyzeScreen(pkg: String) {
        try {
            // Evento de janela flutuante de um app que não está na frente: ignora sem esconder nada.
            val root = foregroundRootOf(pkg) ?: return
            val screenTexts = extractor.extract(root)
            val texts = screenTexts.map { it.text }
            resetOcrIfNewScreen(pkg, offerKeyOf(texts))

            val mode = container.analyzeScreen.visualReadingMode(pkg, texts)
            val combined = texts + freshOcrTexts(pkg, mode)

            when (mode) {
                VisualReadingMode.MAP_ONCE_PER_OFFER ->
                    if (container.analyzeScreen.needsVisualReading(pkg, combined)) {
                        recordDiagnostics(pkg, texts, "aguardando leitura do mapa (tentativa ${ocrAttempts + 1})")
                        requestMapReading(mapBottomOf(screenTexts))
                        return
                    }
                VisualReadingMode.LOWER_SCREEN_CONTINUOUS -> requestLowerScreenReading(pkg)
                VisualReadingMode.NONE -> Unit
            }

            val analysis = container.analyzeScreen.analyze(pkg, combined)
            recordDiagnostics(pkg, texts, analysis?.let { container.formatter.headline(it) } ?: "nenhuma oferta reconhecida")
            showOrHide(analysis)
        } catch (e: Exception) {
            container.logger.error(TAG, "Erro ao analisar a tela de $pkg", e)
        }
    }

    private fun showOrHide(analysis: RideAnalysis?) {
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
    }

    // ---- OCR ----

    /** Nova oferta (ou outro app): descarta o OCR anterior. */
    private fun resetOcrIfNewScreen(pkg: String, offerKey: String) {
        if (pkg == ocrPackage && offerKey == ocrOfferKey) return
        ocrPackage = pkg
        ocrOfferKey = offerKey
        ocrTexts = emptyList()
        ocrAttempts = 0
    }

    /** No modo contínuo (Uber), um OCR antigo não representa mais a tela. */
    private fun freshOcrTexts(pkg: String, mode: VisualReadingMode): List<String> {
        if (pkg != ocrPackage) return emptyList()
        val stale = mode == VisualReadingMode.LOWER_SCREEN_CONTINUOUS &&
            SystemClock.elapsedRealtime() - ocrTakenAtMs > CONTINUOUS_OCR_MAX_AGE_MS
        return if (stale) emptyList() else ocrTexts
    }

    /**
     * inDrive: lê o mapa uma vez por oferta. O leitor é escondido antes para não
     * aparecer no print; há algumas tentativas porque o mapa pode estar desenhando.
     */
    private fun requestMapReading(mapBottomPx: Int) {
        if (ocrInFlight || ocrAttempts >= MAX_MAP_OCR_ATTEMPTS || !ocrReader.isSupported) return
        ocrAttempts++
        overlay.hideNow()
        val delay = if (ocrAttempts == 1) MAP_SETTLE_MS else MAP_RETRY_MS
        runOcr(topPx = 0, bottomPx = mapBottomPx, delayMs = delay)
    }

    /**
     * Uber: o cartão bloqueia a acessibilidade, então lemos a metade de baixo da tela
     * em intervalos. O leitor fica no topo, fora da área lida.
     */
    private fun requestLowerScreenReading(pkg: String) {
        if (ocrInFlight || !ocrReader.isSupported) return
        if (SystemClock.elapsedRealtime() - ocrTakenAtMs < CONTINUOUS_OCR_INTERVAL_MS) return
        val height = resources.displayMetrics.heightPixels
        ocrPackage = pkg
        runOcr(topPx = (height * LOWER_SCREEN_START_RATIO).toInt(), bottomPx = height, delayMs = 0)
    }

    private fun runOcr(topPx: Int, bottomPx: Int, delayMs: Long) {
        ocrInFlight = true
        val requestedFor = ocrPackage to ocrOfferKey
        handler.postDelayed({
            ocrReader.read(topPx, bottomPx) { lines ->
                ocrInFlight = false
                ocrTakenAtMs = SystemClock.elapsedRealtime()
                if (requestedFor == (ocrPackage to ocrOfferKey)) {
                    ocrTexts = lines
                    handler.removeCallbacks(analyzeTask)
                    handler.post(analyzeTask)
                }
            }
        }, delayMs)
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

    // ---- Janelas ----

    private fun foregroundPackage(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /**
     * Raiz da janela principal do app, apenas se ele estiver em primeiro plano.
     * Aceita também uma janela de aplicativo do mesmo pacote (cartão de oferta separado).
     */
    private fun foregroundRootOf(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }?.let { return it }
        return runCatching {
            windows.firstOrNull { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.isActive &&
                    window.root?.packageName?.toString() == pkg
            }?.root
        }.getOrNull()
    }

    private fun recordDiagnostics(pkg: String, texts: List<String>, result: String) {
        val source = runCatching {
            "app em primeiro plano: ${foregroundPackage()} | janelas: " +
                windows.joinToString { "${it.root?.packageName}(${windowTypeName(it.type)})" }
        }.getOrDefault("desconhecida")
        container.diagnostics.record(pkg, source, texts, if (pkg == ocrPackage) ocrTexts else emptyList(), result)
    }

    private fun windowTypeName(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "sistema/flutuante"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "teclado"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "leitor"
        else -> "outro"
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
        const val MAP_RETRY_MS = 500L
        const val MAX_MAP_OCR_ATTEMPTS = 3
        const val MAP_FALLBACK_RATIO = 0.4
        const val CONTINUOUS_OCR_INTERVAL_MS = 1_200L
        const val CONTINUOUS_OCR_MAX_AGE_MS = 3_000L
        const val LOWER_SCREEN_START_RATIO = 0.4
    }
}
