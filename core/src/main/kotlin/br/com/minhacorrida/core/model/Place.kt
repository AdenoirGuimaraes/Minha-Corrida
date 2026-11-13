package br.com.minhacorrida.core.model

/**
 * Destino lido na oferta.
 * @property raw endereço como apareceu na tela (usado para localizar no mapa).
 * @property neighborhood bairro (ex.: "Guarujá"); null se a tela não mostrou.
 * @property city cidade (ex.: "Lages"); null se a tela não mostrou.
 */
data class Place(val raw: String, val neighborhood: String?, val city: String?)
