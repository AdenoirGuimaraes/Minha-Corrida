package br.com.minhacorrida.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** LinearLayout sempre quadrado: a altura acompanha a largura (quadrados do card). */
class SquareLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
