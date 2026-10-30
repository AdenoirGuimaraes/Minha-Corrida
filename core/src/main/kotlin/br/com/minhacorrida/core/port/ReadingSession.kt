package br.com.minhacorrida.core.port

/**
 * Liga/desliga a leitura de ofertas sem desativar a Acessibilidade.
 *
 * O Android não deixa um app religar o próprio serviço de acessibilidade; por isso
 * "Encerrar" apenas pausa a leitura (serviço ocioso, sem OCR nem leitor na tela)
 * e abrir o app retoma.
 */
interface ReadingSession {
    /** @return true enquanto o motorista estiver usando o app (leitura ligada). */
    fun isActive(): Boolean

    /** Retoma a leitura (chamado ao abrir o app). */
    fun start()

    /** Pausa a leitura (botão Encerrar). */
    fun stop()
}

/** Implementação em memória, útil em testes. */
class InMemoryReadingSession(private var active: Boolean = true) : ReadingSession {
    override fun isActive() = active
    override fun start() { active = true }
    override fun stop() { active = false }
}
