package br.com.minhacorrida.core.parser

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer

/** Contrato de leitura de oferta para uma plataforma (uma implementação por app). */
interface RideOfferParser {
    val platform: Platform

    /**
     * @param texts textos visíveis na tela (acessibilidade + OCR, se houver), em ordem de leitura.
     * @return a oferta encontrada, ou null se a tela não for de oferta ou faltar dado.
     */
    fun parse(texts: List<String>): RideOffer?

    /**
     * Indica que a tela é uma oferta, mas parte dos dados só existe como imagem
     * (ex.: bolhas do mapa) e precisa de leitura visual (OCR).
     */
    fun needsVisualReading(texts: List<String>): Boolean = false
}
