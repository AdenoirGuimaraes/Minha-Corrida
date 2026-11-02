package br.com.minhacorrida.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import br.com.minhacorrida.R
import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideRating

/**
 * Preenche o layout `overlay_ride` com uma análise. Usado pelo card sobre os apps de
 * corrida e pelo simulador da tela de configurações — os dois ficam sempre iguais.
 */
class RideCardBinder(private val formatter: RideAnalysisFormatter) {

    /**
     * @param platformLabel texto do selo do app; null = nome da plataforma da oferta.
     * @param showCloseHint false no simulador (lá o card não fecha com toque).
     */
    fun bind(root: View, analysis: RideAnalysis, platformLabel: String? = null, showCloseHint: Boolean = true) {
        root.findViewById<TextView>(R.id.tvPlatform).text = platformLabel ?: analysis.offer.platform.displayName
        root.findViewById<TextView>(R.id.tvValuePerKm).text = formatter.headline(analysis)
        root.findViewById<TextView>(R.id.tvDetails).text = formatter.details(analysis)
        root.findViewById<TextView>(R.id.tvMinimumFare).text = formatter.minimumFare(analysis)
        bindOptional(root.findViewById(R.id.tvProfit), formatter.profit(analysis))
        bindOptional(root.findViewById(R.id.tvRating), formatter.passengerRating(analysis))
        root.findViewById<TextView>(R.id.tvTapToClose).visibility = if (showCloseHint) View.VISIBLE else View.GONE
        root.background = GradientDrawable().apply {
            cornerRadius = 18 * root.resources.displayMetrics.density
            setColor(if (analysis.rating == RideRating.GOOD) COLOR_GOOD else COLOR_BAD)
        }
    }

    /** Linha que só aparece quando há dado (lucro sem custos informados, nota de passageiro novo). */
    private fun bindOptional(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private companion object {
        const val COLOR_GOOD = 0xEE1B8A3A.toInt()
        const val COLOR_BAD = 0xEEC62828.toInt()
    }
}
