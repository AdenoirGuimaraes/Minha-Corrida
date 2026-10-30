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
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.port.AppLogger

/**
 * Mostra o resultado sobre o app de corrida usando TYPE_ACCESSIBILITY_OVERLAY
 * (não exige a permissão "sobrepor a outros apps"). O overlay não recebe toques,
 * então nunca bloqueia o botão "Aceitar".
 */
class RideOverlayController(
    private val service: AccessibilityService,
    private val formatter: RideAnalysisFormatter,
    private val logger: AppLogger,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { hideNow() }
    private var view: View? = null

    /** Exibe/atualiza o overlay e renova o tempo máximo de exibição. */
    fun show(analysis: RideAnalysis) {
        try {
            val current = view ?: createView().also {
                windowManager.addView(it, buildLayoutParams())
                view = it
            }
            bind(current, analysis)
            handler.removeCallbacks(hideTask)
            handler.postDelayed(hideTask, MAX_VISIBLE_MS)
        } catch (e: Exception) {
            logger.error(TAG, "Não foi possível exibir o overlay", e)
            view = null
        }
    }

    /** Esconde após um curto intervalo (evita piscar em transições de tela). */
    fun scheduleHide() {
        if (view == null) return
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, HIDE_GRACE_MS)
    }

    fun hideNow() {
        handler.removeCallbacks(hideTask)
        val current = view ?: return
        try {
            windowManager.removeView(current)
        } catch (e: Exception) {
            logger.warn(TAG, "Overlay já removido", e)
        } finally {
            view = null
        }
    }

    private fun createView(): View = LayoutInflater.from(service).inflate(R.layout.overlay_ride, null)

    private fun bind(root: View, analysis: RideAnalysis) {
        root.findViewById<TextView>(R.id.tvPlatform).text = analysis.offer.platform.displayName
        root.findViewById<TextView>(R.id.tvValuePerKm).text = formatter.headline(analysis)
        root.findViewById<TextView>(R.id.tvDetails).text = formatter.details(analysis)
        root.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (analysis.rating == RideRating.GOOD) COLOR_GOOD else COLOR_BAD)
        }
    }

    private fun buildLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
        const val MAX_VISIBLE_MS = 45_000L
        const val TOP_OFFSET_DP = 70
        const val COLOR_GOOD = 0xEE1B8A3A.toInt()
        const val COLOR_BAD = 0xEEC62828.toInt()
    }
}
