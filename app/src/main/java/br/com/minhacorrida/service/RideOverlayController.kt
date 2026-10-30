package br.com.minhacorrida.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import br.com.minhacorrida.R
import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.port.AppLogger

/**
 * Mostra o resultado sobre o app de corrida usando TYPE_ACCESSIBILITY_OVERLAY
 * (não exige a permissão "sobrepor a outros apps").
 *
 * Fecha com um toque ou após o tempo configurado. Toques fora do quadro passam
 * direto para o app de corrida, então o botão "Aceitar" continua livre.
 *
 * @param onDismissed chamado quando o motorista ou o tempo fecha o leitor.
 */
class RideOverlayController(
    private val service: AccessibilityService,
    private val formatter: RideAnalysisFormatter,
    private val logger: AppLogger,
    private val onDismissed: (RideAnalysis) -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { hideNow() }
    private val autoCloseTask = Runnable { dismiss() }

    private var view: View? = null
    private var shownOffer: RideOffer? = null
    private var currentAnalysis: RideAnalysis? = null

    /**
     * Exibe/atualiza o leitor. O cronômetro de fechamento começa quando uma oferta
     * nova aparece e não é renovado pelos eventos repetidos da mesma tela.
     */
    fun show(analysis: RideAnalysis, autoCloseSeconds: Int) {
        try {
            val current = view ?: createView().also {
                windowManager.addView(it, buildLayoutParams())
                view = it
            }
            handler.removeCallbacks(hideTask)

            if (analysis.offer != shownOffer) {
                shownOffer = analysis.offer
                handler.removeCallbacks(autoCloseTask)
                handler.postDelayed(autoCloseTask, autoCloseSeconds * 1_000L)
            }
            currentAnalysis = analysis
            bind(current, analysis)
        } catch (e: Exception) {
            logger.error(TAG, "Não foi possível exibir o leitor", e)
            view = null
        }
    }

    /** A oferta sumiu da tela: esconde após um curto intervalo (evita piscar). */
    fun scheduleHide() {
        if (view == null) return
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, HIDE_GRACE_MS)
    }

    /** Remove o leitor imediatamente, sem marcar a oferta como fechada. */
    fun hideNow() {
        handler.removeCallbacks(hideTask)
        handler.removeCallbacks(autoCloseTask)
        val current = view
        view = null
        shownOffer = null
        currentAnalysis = null
        if (current == null) return
        try {
            windowManager.removeView(current)
        } catch (e: Exception) {
            logger.warn(TAG, "Leitor já removido", e)
        }
    }

    /** Fechamento pelo motorista (toque) ou pelo tempo. */
    private fun dismiss() {
        val analysis = currentAnalysis
        hideNow()
        analysis?.let(onDismissed)
    }

    private fun createView(): View =
        LayoutInflater.from(service).inflate(R.layout.overlay_ride, null).apply {
            setOnClickListener { dismiss() }
        }

    private fun bind(root: View, analysis: RideAnalysis) {
        root.findViewById<TextView>(R.id.tvPlatform).text = analysis.offer.platform.displayName
        root.findViewById<TextView>(R.id.tvValuePerKm).text = formatter.headline(analysis)
        root.findViewById<TextView>(R.id.tvDetails).text = formatter.details(analysis)
        root.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (analysis.rating == RideRating.GOOD) COLOR_GOOD else COLOR_BAD)
        }
    }

    // FLAG_NOT_TOUCH_MODAL: só o quadro recebe toques; o resto da tela segue para o app.
    private fun buildLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(TOP_OFFSET_DP)
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "RideOverlay"
        const val HIDE_GRACE_MS = 1_200L
        const val TOP_OFFSET_DP = 70
        const val COLOR_GOOD = 0xEE1B8A3A.toInt()
        const val COLOR_BAD = 0xEEC62828.toInt()
    }
}
