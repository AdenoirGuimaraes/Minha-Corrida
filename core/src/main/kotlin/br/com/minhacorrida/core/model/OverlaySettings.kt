package br.com.minhacorrida.core.model

/** Limites do tempo de exibição do leitor na tela. */
object OverlayTimeout {
    const val MIN_SECONDS = 3
    const val MAX_SECONDS = 120
    const val DEFAULT_SECONDS = 10

    fun isValid(seconds: Int): Boolean = seconds in MIN_SECONDS..MAX_SECONDS
}
