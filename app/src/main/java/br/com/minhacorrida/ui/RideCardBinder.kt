package br.com.minhacorrida.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import br.com.minhacorrida.R
import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideRating

/**
 * Preenche o layout `overlay_ride` (card de 3 quadrados) com uma análise. Usado pelo card
 * sobre os apps de corrida e pelo simulador — os dois ficam sempre iguais.
 *
 * Cores: fundo verde (corrida boa) ou vermelho (abaixo do mínimo); faixa do topo na cor
 * do app — Uber preta com letras brancas, inDrive verde-limão com letras pretas.
 */
class RideCardBinder(private val formatter: RideAnalysisFormatter) {

    /** @param showCloseHint false esconde o "toque para fechar" (hoje sempre visível). */
    fun bind(root: View, analysis: RideAnalysis, showCloseHint: Boolean = true) {
        val text = formatter.card(analysis)
        val density = root.resources.displayMetrics.density

        val brand = brandColors(analysis.offer.platform)
        root.findViewById<View>(R.id.platformStrip).background = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(brand.background)
        }
        root.findViewById<TextView>(R.id.tvPlatform).apply {
            this.text = text.platform
            setTextColor(brand.text)
        }
        root.findViewById<TextView>(R.id.tvRating).apply {
            this.text = text.rating.orEmpty()
            setTextColor(brand.text)
            visibility = if (text.rating == null) View.GONE else View.VISIBLE
        }

        root.findViewById<TextView>(R.id.tvValuePerKm).text = text.valuePerKm
        root.findViewById<TextView>(R.id.tvDetails).text = text.kmDetails
        root.findViewById<TextView>(R.id.tvProfitLabel).text = text.profitLabel
        root.findViewById<TextView>(R.id.tvProfit).text = text.profitValue
        root.findViewById<TextView>(R.id.tvProfitDetails).text = text.profitDetails
        root.findViewById<TextView>(R.id.tvMinimumFare).text = text.minimumValue
        root.findViewById<TextView>(R.id.tvMinimumLabel).text = text.minimumLabel
        root.findViewById<TextView>(R.id.tvTapToClose).visibility = if (showCloseHint) View.VISIBLE else View.GONE

        root.background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(if (analysis.rating == RideRating.GOOD) COLOR_GOOD else COLOR_BAD)
        }
    }

    private data class BrandColors(val background: Int, val text: Int)

    private fun brandColors(platform: Platform): BrandColors = when (platform) {
        Platform.UBER -> BrandColors(background = UBER_BLACK, text = WHITE)
        Platform.INDRIVE -> BrandColors(background = INDRIVE_LIME, text = BLACK)
    }

    private companion object {
        const val COLOR_GOOD = 0xF01B8A3A.toInt()
        const val COLOR_BAD = 0xF0C62828.toInt()
        const val UBER_BLACK = 0xFF000000.toInt()
        const val INDRIVE_LIME = 0xFFC1F11D.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
