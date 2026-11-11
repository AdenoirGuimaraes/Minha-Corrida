package br.com.minhacorrida.core

import br.com.minhacorrida.core.port.InMemoryReadingSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingSessionTest {
    @Test
    fun `leitura comeca ligada, encerra e volta ao abrir o app`() {
        val session = InMemoryReadingSession()
        assertTrue(session.isActive())
        session.stop()
        assertFalse(session.isActive())
        session.start()
        assertTrue(session.isActive())
    }

    @Test
    fun `pausa pelo botao do app nao e desfeita ao abrir o app`() {
        val session = InMemoryReadingSession()
        session.setPausedByUser(true)
        session.start()                       // abrir o app
        assertFalse(session.isActive())
        session.setPausedByUser(false)
        assertTrue(session.isActive())
    }
}
