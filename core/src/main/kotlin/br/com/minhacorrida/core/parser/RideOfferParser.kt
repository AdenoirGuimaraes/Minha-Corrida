package br.com.minhacorrida.core.parser

import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideOffer

/** Contrato de leitura de oferta para uma plataforma (uma implementação por app). */
interface RideOfferParser {
    val platform: Platform

    /**
     * @param texts textos visíveis na tela, na ordem de leitura.
     * @return a oferta encontrada, ou null se a tela não for de oferta.
     */
    fun parse(texts: List<String>): RideOffer?
}
