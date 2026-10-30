package br.com.minhacorrida.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.di.AppContainer

/**
 * Serviço de acessibilidade: escuta mudanças de tela dos apps de corrida,
 * extrai os textos, calcula o R$/km e mostra o overlay.
 */
class RideReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var container: AppContainer
    private lateinit var extractor: ScreenTextExtractor
    private lateinit var overlay: RideOverlayController

    private var pendingPackage: String? = null
    private var lastAnalysis: RideAnalysis? = null
    private val analyzeTask = Runnable { pendingPackage?.let(::analyzeScreen) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            container = (application as MinhaCorridaApp).container
            extractor = ScreenTextExtractor(container.logger)
            overlay = RideOverlayController(this, container.formatter, container.logger)
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

    /** Esconde o overlay quando o motorista troca para outro app. */
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
            val texts = extractor.extract(root)
            val analysis = container.analyzeScreen.analyze(pkg, texts)

            if (analysis == null) {
                lastAnalysis = null
                overlay.scheduleHide()
                return
            }
            if (analysis != lastAnalysis) {
                lastAnalysis = analysis
                container.logger.debug(TAG, "Oferta: $analysis")
            }
            overlay.show(analysis)
        } catch (e: Exception) {
            container.logger.error(TAG, "Erro ao analisar a tela de $pkg", e)
        }
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
        super.onDestroy()
    }

    private companion object {
        const val TAG = "RideReaderService"
        const val DEBOUNCE_MS = 120L
    }
}
