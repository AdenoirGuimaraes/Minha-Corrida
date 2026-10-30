package br.com.minhacorrida.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.overlay.OverlayVisibilityPolicy
import br.com.minhacorrida.core.parser.VisualReadingMode
import br.com.minhacorrida.core.reading.ReadingPriorityPolicy
import br.com.minhacorrida.core.reading.ReadingTarget
import br.com.minhacorrida.core.reading.WindowSnapshot
import br.com.minhacorrida.di.AppContainer

/**
 * Serviço de acessibilidade: escuta mudanças de tela dos apps de corrida,
 * extrai os textos (e, quando preciso, lê a tela com OCR), calcula o R$/km
 * e mostra o leitor.
 *
 * Prioridade de leitura (ver [ReadingPriorityPolicy]):
 *  1º  cartão de oferta da Uber em sobreposição, mesmo com o inDrive na frente;
 *  2º  app de corrida em primeiro plano (oferta do inDrive em tela inteira);
 *  3º  nada.
 * Só um alvo é lido por vez; quando o cartão da Uber some, o inDrive recomeça
 * a leitura do mapa do zero.
 */
class RideReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val visibility = OverlayVisibilityPolicy()
    private val priority = ReadingPriorityPolicy()
    private lateinit var container: AppContainer
    private lateinit var extractor: ScreenTextExtractor
    private lateinit var ocrReader: ScreenOcrReader
    private lateinit var overlay: RideOverlayController

    private var currentTarget: ReadingTarget? = null
    private var lastAnalysis: RideAnalysis? = null
    private val analyzeTask = Runnable { evaluate() }
    private val pollTask = Runnable { evaluate() }

    // Estado da leitura visual (OCR) — pertence sempre a um único alvo.
    private var ocrOfferKey: String? = null
    private var ocrPackage: String? = null
    private var ocrTexts: List<String> = emptyList()
    private var ocrTakenAtMs = 0L
    private var ocrAttempts = 0
    private var ocrInFlight = false
    private var lastContinuousOcrMs = 0L

    // Sobreposição grande da Uber que não é oferta (ex.: aviso): após algumas
    // leituras sem oferta, é ignorada para não travar o inDrive.
    private var uberCardKey: String? = null
    private var uberCardMisses = 0
    private var uberCardCountedOcrAt = 0L
    private var ignoredUberCardKey: String? = null

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
        if (!::overlay.isInitialized || event == null) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return // o próprio leitor

        // Abertura/fechamento de janelas (ex.: cartão da Uber surgindo sobre o inDrive).
        val windowsChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (pkg != null && !windowsChanged && !container.parserRegistry.supports(pkg)) {
            handleForeignPackage(event, pkg)
            return
        }

        // Debounce: a tela dispara dezenas de eventos por segundo; avaliamos só o último.
        handler.removeCallbacks(analyzeTask)
        handler.postDelayed(analyzeTask, DEBOUNCE_MS)
    }

    /** Esconde o leitor quando o motorista troca para outro app (sem cartão da Uber na tela). */
    private fun handleForeignPackage(event: AccessibilityEvent, pkg: String) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (foregroundPackage() == pkg && currentTarget?.source != ReadingTarget.Source.UBER_CARD_OVERLAY) {
            lastAnalysis = null
            overlay.scheduleHide()
        }
    }

    // ---- Escolha do alvo ----

    private fun evaluate() {
        handler.removeCallbacks(pollTask)
        try {
            val target = chooseTarget()
            if (target == null) {
                if (currentTarget != null) {
                    container.logger.debug(TAG, "Nenhum app de corrida para ler")
                    currentTarget = null
                    lastAnalysis = null
                    overlay.scheduleHide()
                }
                return
            }
            if (target.packageName != currentTarget?.packageName || target.source != currentTarget?.source) {
                container.logger.debug(TAG, "Leitura assumida por: ${target.describe()}")
            }
            currentTarget = target
            analyzeScreen(target)
        } catch (e: Exception) {
            container.logger.error(TAG, "Erro ao escolher a tela a ler", e)
        }
    }

    private fun chooseTarget(): ReadingTarget? {
        val list = runCatching { windows }.getOrNull() ?: return null
        val bounds = Rect()
        val snapshots = list.map { w ->
            w.getBoundsInScreen(bounds)
            WindowSnapshot(
                packageName = runCatching { w.root?.packageName?.toString() }.getOrNull(),
                isActive = w.isActive,
                isApplication = w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom,
            )
        }
        val dm = resources.displayMetrics
        val withCard = priority.choose(snapshots, dm.widthPixels, dm.heightPixels)
        val cardKey = withCard?.takeIf { it.source == ReadingTarget.Source.UBER_CARD_OVERLAY }
            ?.let { "${it.ocrTopPx}-${it.ocrBottomPx}" }

        if (cardKey != uberCardKey) {
            // Cartão novo (ou sumiu): zera a contagem e volta a confiar nele.
            uberCardKey = cardKey
            uberCardMisses = 0
            uberCardCountedOcrAt = 0L
            ignoredUberCardKey = null
        }
        return if (cardKey != null && cardKey == ignoredUberCardKey) {
            priority.choose(snapshots, dm.widthPixels, dm.heightPixels, ignoreUberCard = true)
        } else {
            withCard
        }
    }

    // ---- Leitura do alvo ----

    private fun analyzeScreen(target: ReadingTarget) {
        val pkg = target.packageName
        val isCard = target.source == ReadingTarget.Source.UBER_CARD_OVERLAY
        try {
            val root = if (isCard) uberCardRoot() else (foregroundRootOf(pkg) ?: return)
            val screenTexts = root?.let(extractor::extract).orEmpty()
            val texts = screenTexts.map { it.text }
            resetOcrIfNewScreen(pkg, target.source.name + "|" + offerKeyOf(texts))

            val mode = container.analyzeScreen.visualReadingMode(pkg, texts)
            val ocr = freshOcrTexts(pkg, mode)
            // Uber: o cartão vem do OCR e deve vir antes dos textos da tela inicial
            // (ganhos do dia, ex. "R$ 13,92"), para o valor da oferta ser o lido primeiro.
            val combined = if (mode == VisualReadingMode.LOWER_SCREEN_CONTINUOUS) ocr + texts else texts + ocr

            when (mode) {
                VisualReadingMode.MAP_ONCE_PER_OFFER ->
                    if (container.analyzeScreen.needsVisualReading(pkg, combined)) {
                        recordDiagnostics(target, texts, "aguardando leitura do mapa (tentativa ${ocrAttempts + 1})")
                        requestMapReading(mapBottomOf(screenTexts))
                        return
                    }
                VisualReadingMode.LOWER_SCREEN_CONTINUOUS -> {
                    val height = resources.displayMetrics.heightPixels
                    val top = target.ocrTopPx ?: (height * LOWER_SCREEN_START_RATIO).toInt()
                    val bottom = target.ocrBottomPx ?: height
                    requestLowerScreenReading(pkg, top, bottom)
                    // O cartão sobreposto pode não gerar eventos: relê enquanto ele estiver na tela.
                    if (isCard) handler.postDelayed(pollTask, CONTINUOUS_OCR_INTERVAL_MS)
                }
                VisualReadingMode.NONE -> Unit
            }

            val analysis = container.analyzeScreen.analyze(pkg, combined)
            if (isCard) countUberCardResult(analysis)
            recordDiagnostics(target, texts, analysis?.let { container.formatter.headline(it) } ?: "nenhuma oferta reconhecida")
            showOrHide(analysis)
        } catch (e: Exception) {
            container.logger.error(TAG, "Erro ao analisar a tela de $pkg", e)
        }
    }

    /** Conta leituras de OCR do cartão que não formaram oferta (uma vez por OCR novo). */
    private fun countUberCardResult(analysis: RideAnalysis?) {
        if (analysis != null) {
            uberCardMisses = 0
            return
        }
        if (ocrTakenAtMs == 0L || ocrTakenAtMs == uberCardCountedOcrAt || ocrPackage != currentTarget?.packageName) return
        uberCardCountedOcrAt = ocrTakenAtMs
        uberCardMisses++
        if (uberCardMisses >= MAX_UBER_CARD_MISSES) {
            container.logger.debug(TAG, "Sobreposição da Uber sem oferta: devolvendo a leitura ao app da frente")
            ignoredUberCardKey = uberCardKey
            handler.removeCallbacks(analyzeTask)
            handler.post(analyzeTask)
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

    /** Nova oferta (ou outro alvo): descarta o OCR anterior e as tentativas do mapa. */
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
     * Uber: o cartão bloqueia a acessibilidade, então lemos por OCR a faixa do cartão
     * (ou a metade de baixo, com a Uber em tela inteira) em intervalos.
     * O leitor fica no topo, fora da área lida.
     */
    private fun requestLowerScreenReading(pkg: String, topPx: Int, bottomPx: Int) {
        if (ocrInFlight || !ocrReader.isSupported) return
        // Intervalo próprio: um OCR do mapa do inDrive não atrasa a leitura do cartão.
        val now = SystemClock.elapsedRealtime()
        if (now - lastContinuousOcrMs < CONTINUOUS_OCR_INTERVAL_MS) return
        lastContinuousOcrMs = now
        ocrPackage = pkg
        runOcr(topPx = topPx, bottomPx = bottomPx, delayMs = 0)
    }

    private fun runOcr(topPx: Int, bottomPx: Int, delayMs: Long) {
        ocrInFlight = true
        val requestedFor = ocrPackage to ocrOfferKey
        handler.postDelayed({
            ocrReader.read(topPx, bottomPx) { lines ->
                ocrInFlight = false
                ocrTakenAtMs = SystemClock.elapsedRealtime()
                // Resultado de um alvo que já perdeu a vez (ex.: mapa do inDrive quando
                // o cartão da Uber apareceu) é descartado.
                if (requestedFor == (ocrPackage to ocrOfferKey)) ocrTexts = lines
                handler.removeCallbacks(analyzeTask)
                handler.post(analyzeTask)
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

    /** Raiz da maior janela da Uber que não é a ativa (o cartão sobreposto). */
    private fun uberCardRoot(): AccessibilityNodeInfo? = runCatching {
        val bounds = Rect()
        windows
            .filter { !it.isActive && Platform.fromPackage(it.root?.packageName?.toString().orEmpty()) == Platform.UBER }
            .maxByOrNull { it.getBoundsInScreen(bounds); bounds.height() }
            ?.root
    }.getOrNull()

    private fun recordDiagnostics(target: ReadingTarget, texts: List<String>, result: String) {
        val pkg = target.packageName
        val source = runCatching {
            "alvo: ${target.describe()} | app em primeiro plano: ${foregroundPackage()} | janelas: " +
                windows.joinToString { w ->
                    val r = Rect().also(w::getBoundsInScreen)
                    "${w.root?.packageName}(${windowTypeName(w.type)} y=${r.top}–${r.bottom})"
                }
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
        const val MAX_UBER_CARD_MISSES = 4
    }
}
