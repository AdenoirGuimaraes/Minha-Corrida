package br.com.minhacorrida.core.port

/**
 * Liga/desliga a leitura de ofertas sem desativar a Acessibilidade.
 *
 * O Android não deixa um app religar o próprio serviço de acessibilidade; por isso
 * "Encerrar" apenas pausa a leitura (serviço ocioso, sem OCR nem leitor na tela)
 * e abrir o app retoma.
 */
interface ReadingSession {
    /** @return true enquanto a leitura estiver ligada (app em uso e não pausada pelo motorista). */
    fun isActive(): Boolean

    /**
     * Pausa feita pelo botão do app (ex.: conferir o histórico da Uber sem o leitor achar
     * que são ofertas). Diferente de [stop], abrir o app NÃO desfaz: só o mesmo botão religa.
     */
    fun isPausedByUser(): Boolean

    fun setPausedByUser(paused: Boolean)

    /** Retoma a leitura (chamado ao abrir o app). */
    fun start()

    /** Pausa a leitura (botão Encerrar). */
    fun stop()
}

/** Implementação em memória, útil em testes. */
class InMemoryReadingSession(private var active: Boolean = true) : ReadingSession {
    private var paused = false
    override fun isActive() = active && !paused
    override fun isPausedByUser() = paused
    override fun setPausedByUser(paused: Boolean) { this.paused = paused }
    override fun start() { active = true }
    override fun stop() { active = false }
}
