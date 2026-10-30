package br.com.minhacorrida.core.parser

import br.com.minhacorrida.core.model.Platform

/** Seleciona o parser correto a partir do pacote do app em primeiro plano. */
class ParserRegistry(parsers: List<RideOfferParser>) {

    private val byPlatform: Map<Platform, RideOfferParser> = parsers.associateBy { it.platform }

    fun supports(packageName: String): Boolean = parserFor(packageName) != null

    fun parserFor(packageName: String): RideOfferParser? =
        Platform.fromPackage(packageName)?.let(byPlatform::get)
}
