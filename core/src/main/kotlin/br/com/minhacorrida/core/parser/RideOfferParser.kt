package br.com.minhacorrida.core.parser

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer

/** Como a tela precisa ser lida visualmente (OCR) quando a acessibilidade não basta. */
enum class VisualReadingMode {
    /** Acessibilidade é suficiente. */
    NONE,

    /** Lê uma vez por oferta a faixa ACIMA do cartão (mapa do inDrive). */
    MAP_ONCE_PER_OFFER,

    /** Lê continuamente a METADE DE BAIXO da tela (cartão da Uber, que bloqueia a acessibilidade). */
    LOWER_SCREEN_CONTINUOUS,
}

/** Contrato de leitura de oferta para uma plataforma (uma implementação por app). */
interface RideOfferParser {
    val platform: Platform

    /**
     * @param texts textos visíveis na tela (acessibilidade + OCR, se houver), em ordem de leitura.
     * @return a oferta encontrada, ou null se a tela não for de oferta ou faltar dado.
     */
    fun parse(texts: List<String>): RideOffer?

    /**
     * @param texts textos obtidos SOMENTE pela acessibilidade.
     * @return o tipo de leitura visual necessária para completar a oferta.
     */
    fun visualReadingMode(texts: List<String>): VisualReadingMode = VisualReadingMode.NONE

    /** Atalho: true quando há leitura visual pendente. */
    fun needsVisualReading(texts: List<String>): Boolean = visualReadingMode(texts) != VisualReadingMode.NONE
}
