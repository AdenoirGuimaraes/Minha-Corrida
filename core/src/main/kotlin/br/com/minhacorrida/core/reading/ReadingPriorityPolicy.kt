package br.com.minhacorrida.core.reading

import br.com.minhacorrida.core.model.Platform

/** Retrato de uma janela da tela, sem dependência do Android (testável na JVM). */
data class WindowSnapshot(
    val packageName: String?,
    val isActive: Boolean,
    val isApplication: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** O que deve ser lido agora e, para o cartão da Uber, a faixa da tela do OCR. */
data class ReadingTarget(
    val packageName: String,
    val source: Source,
    val ocrTopPx: Int? = null,
    val ocrBottomPx: Int? = null,
) {
    enum class Source {
        /** Cartão de oferta da Uber aberto por cima de outro app (ex.: inDrive na frente). */
        UBER_CARD_OVERLAY,

        /** App de corrida em primeiro plano (tela inteira). */
        FOREGROUND,
    }

    fun describe(): String = when (source) {
        Source.UBER_CARD_OVERLAY -> "cartão Uber sobreposto (OCR y=$ocrTopPx–$ocrBottomPx)"
        Source.FOREGROUND -> "$packageName em primeiro plano"
    }
}

/**
 * Decide qual tela ler quando há mais de um app de corrida aberto.
 *
 *  1º  Cartão de oferta da Uber em sobreposição: sempre tem preferência, mesmo
 *      com o inDrive (ou qualquer outro app) em primeiro plano.
 *  2º  App de corrida em primeiro plano (ex.: oferta do inDrive em tela inteira).
 *  3º  Nada a ler.
 *
 * O botão flutuante "Uber →" é pequeno e não conta como cartão; o cartão é uma
 * janela larga e alta da Uber que não é a janela ativa. A faixa do OCR é a área
 * dessas janelas (partes de cima e de baixo do cartão juntas), nunca a tela toda,
 * para não misturar textos do inDrive que está atrás.
 */
class ReadingPriorityPolicy(
    private val minCardHeightRatio: Double = 0.18,
    private val minCardWidthRatio: Double = 0.5,
    private val fullScreenRatio: Double = 0.85,
    private val lowerScreenStartRatio: Double = 0.4,
    private val minOcrTopRatio: Double = 0.2,
) {

    /**
     * @param windows janelas visíveis, em qualquer ordem.
     * @param ignoreUberCard true para pular o cartão (ex.: sobreposição da Uber que não é oferta).
     * @return o alvo da leitura, ou null se nenhum app de corrida estiver visível.
     */
    fun choose(
        windows: List<WindowSnapshot>,
        screenWidth: Int,
        screenHeight: Int,
        ignoreUberCard: Boolean = false,
    ): ReadingTarget? {
        if (!ignoreUberCard) uberCard(windows, screenWidth, screenHeight)?.let { return it }

        return windows
            .firstOrNull { it.isActive && it.isApplication && platformOf(it) != null }
            ?.let { ReadingTarget(it.packageName!!, ReadingTarget.Source.FOREGROUND) }
    }

    private fun uberCard(windows: List<WindowSnapshot>, screenWidth: Int, screenHeight: Int): ReadingTarget? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val cards = windows.filter { w ->
            platformOf(w) == Platform.UBER &&
                !w.isActive &&
                w.height >= screenHeight * minCardHeightRatio &&
                w.width >= screenWidth * minCardWidthRatio
        }
        if (cards.isEmpty()) return null

        val top = cards.minOf { it.top }.coerceAtLeast(0)
        val bottom = cards.maxOf { it.bottom }.coerceAtMost(screenHeight)
        // Sobreposição do tamanho da tela: o cartão fica embaixo, como na Uber em primeiro plano.
        val ocrTop = if (bottom - top >= screenHeight * fullScreenRatio) {
            (screenHeight * lowerScreenStartRatio).toInt()
        } else {
            // Nunca sobe até o topo, onde fica o próprio leitor do Minha Corrida.
            top.coerceAtLeast((screenHeight * minOcrTopRatio).toInt())
        }
        if (bottom <= ocrTop) return null
        return ReadingTarget(cards.first().packageName!!, ReadingTarget.Source.UBER_CARD_OVERLAY, ocrTop, bottom)
    }

    private fun platformOf(w: WindowSnapshot): Platform? = w.packageName?.let(Platform::fromPackage)
}
