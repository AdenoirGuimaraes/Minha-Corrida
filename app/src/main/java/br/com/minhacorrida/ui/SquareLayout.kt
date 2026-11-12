package br.com.minhacorrida.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.max

/**
 * Quadrado do card: no mínimo tão alto quanto largo, mas CRESCE quando o conteúdo precisa
 * (antes a altura era travada na largura e a 3ª linha sumia — print de 12/11, "R$ 0,60/min"
 * do Mín. verde cortado). Com altura match_parent na linha, os três quadrados ficam iguais.
 */
class SquareLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // 1º: altura que o conteúdo pede.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        var height = max(width, measuredHeight)
        // A linha pede a mesma altura para os três (o mais alto manda).
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            height = max(height, MeasureSpec.getSize(heightMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
