package br.com.minhacorrida.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import br.com.minhacorrida.R
import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.format.RideCardText
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideRating

/**
 * Preenche o layout `overlay_ride` (card de 3 quadrados) com uma análise. Usado pelo card
 * sobre os apps de corrida e pelo simulador — os dois ficam sempre iguais.
 *
 * Cores: fundo verde (boa), amarelo (R$/km ok, lucro abaixo do mínimo) ou vermelho
 * (abaixo do mínimo R$/km ou nota ruim); faixa do topo na cor
 * do app — Uber preta com letras brancas, inDrive verde-limão com letras pretas.
 */
class RideCardBinder(private val formatter: RideAnalysisFormatter) {

    /** @param showCloseHint false esconde o "toque para fechar" (hoje sempre visível). */
    fun bind(root: View, analysis: RideAnalysis, showCloseHint: Boolean = true) {
        bindTexts(root, formatter.card(analysis), analysis.offer.platform, analysis.rating)
        root.findViewById<TextView>(R.id.tvTapToClose).visibility = if (showCloseHint) View.VISIBLE else View.GONE
    }

    /**
     * Mesmo card para uma corrida já guardada (aba Corridas): textos prontos de
     * [br.com.minhacorrida.core.format.RideHistoryFormatter.card] e a cor guardada da corrida.
     * O texto de baixo (no lugar de "toque para fechar") fica por conta de quem chama.
     */
    fun bindTexts(root: View, text: RideCardText, platform: Platform, rating: RideRating) {
        val density = root.resources.displayMetrics.density

        val brand = brandColors(platform)
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

        bindPanel(root, text.panel, density)

        root.background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(
                when (rating) {
                    RideRating.GOOD -> COLOR_GOOD
                    RideRating.LOW_PROFIT -> COLOR_LOW_PROFIT
                    RideRating.BELOW_MINIMUM -> COLOR_BAD
                },
            )
        }
    }

    /**
     * Painel de baixo: DESTINO [etiqueta] bairro · BUSCA (destaque amarelo se longa) · VOLTA (só
     * bairro longe) · aviso do "não vou". Some quando não há painel (card do histórico).
     */
    private fun bindPanel(root: View, panel: br.com.minhacorrida.core.format.CardPanel?, density: Float) {
        val box = root.findViewById<View>(R.id.destinationPanel) ?: return
        if (panel == null) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        root.findViewById<View>(R.id.rowDestination).visibility = if (panel.destination == null) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvDestName).apply {
            this.text = panel.destination.orEmpty()
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.findViewById<TextView>(R.id.tvDestPill).apply {
            val colors = pillColors(panel.mark, panel.pill)
            visibility = if (panel.pill == null) View.GONE else View.VISIBLE
            this.text = panel.pill.orEmpty()
            setTextColor(colors.second)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(colors.first)
            }
        }
        root.findViewById<TextView>(R.id.tvPickup).apply {
            this.text = panel.pickup
            if (panel.pickupLong) {
                setTextColor(BLACK)
                background = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(LONG_PICKUP)
                }
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            } else {
                setTextColor(WHITE)
                background = null
                setPadding(0, 0, 0, 0)
            }
        }
        root.findViewById<View>(R.id.rowReturn).visibility = if (panel.returnLine == null) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvReturn).text = panel.returnLine.orEmpty()
        root.findViewById<TextView>(R.id.tvPanelNote).apply {
            visibility = if (panel.note == null) View.GONE else View.VISIBLE
            this.text = panel.note.orEmpty()
        }
    }

    /** Etiqueta: fundo claro e texto escuro na cor da marcação (bom verde, longe âmbar, não vou vermelho). */
    private fun pillColors(mark: br.com.minhacorrida.core.neighborhood.NeighborhoodMark?, pill: String?): Pair<Int, Int> =
        when {
            mark == br.com.minhacorrida.core.neighborhood.NeighborhoodMark.GOOD -> 0xFFCFF5D4.toInt() to 0xFF0F3D17.toInt()
            mark == br.com.minhacorrida.core.neighborhood.NeighborhoodMark.AVOID -> 0xFFFFD6D2.toInt() to 0xFF5A0F0A.toInt()
            pill != null -> 0xFFFFE7A3.toInt() to 0xFF4A3700.toInt()
            else -> WHITE to BLACK
        }

    private data class BrandColors(val background: Int, val text: Int)

    private fun brandColors(platform: Platform): BrandColors = when (platform) {
        Platform.UBER -> BrandColors(background = UBER_BLACK, text = WHITE)
        Platform.INDRIVE -> BrandColors(background = INDRIVE_LIME, text = BLACK)
        Platform.PARTICULAR -> BrandColors(background = PARTICULAR_YELLOW, text = BLACK)
    }

    private companion object {
        const val COLOR_GOOD = 0xF01B8A3A.toInt()
        const val COLOR_BAD = 0xF0C62828.toInt()
        /** Amarelo escuro: R$/km ok, lucro abaixo do mínimo (texto branco continua legível). */
        const val COLOR_LOW_PROFIT = 0xF0B8860B.toInt()
        const val UBER_BLACK = 0xFF000000.toInt()
        const val INDRIVE_LIME = 0xFFC1F11D.toInt()
        const val PARTICULAR_YELLOW = 0xFFFFC107.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val LONG_PICKUP = 0xFFF5D16A.toInt()
    }
}
