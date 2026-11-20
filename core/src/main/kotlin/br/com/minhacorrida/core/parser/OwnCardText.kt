package br.com.minhacorrida.core.parser

/**
 * Proteção extra para o OCR não ler o PRÓPRIO card do Minha Corrida (19/11: o painel de baixo,
 * "Prejuízo com a volta: R$ 0,10", foi lido como o valor da corrida da Uber = R$ 0,02/km).
 * O leitor já ignora a área do card na captura; isto tira o que escapar (card em movimento).
 */
object OwnCardText {
    private val MARKERS = listOf(
        "toque para fechar", "lucro com a volta", "prejuízo com a volta", "prejuizo com a volta",
        "mín. verde", "min. verde", "busca longa", "pelo lucro", "pelo r$", "+ volta", "não vou", "nao vou",
        "outra cidade", "líquido r$", "liquido r$", "gasto r$",
    )
    private val LABELS = setOf("volta", "destino", "busca", "lucro", "r$/km")

    /** Linhas do OCR sem as que são do card do app. */
    fun strip(lines: List<String>): List<String> = lines.filterNot { isOwn(it) }

    fun isOwn(line: String): Boolean {
        val t = line.trim().lowercase()
        return t in LABELS || MARKERS.any { t.contains(it) }
    }
}
