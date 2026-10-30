package br.com.minhacorrida.core

import br.com.minhacorrida.core.port.NoOpLogger
import br.com.minhacorrida.core.update.AppRelease
import br.com.minhacorrida.core.update.CheckForUpdateUseCase
import br.com.minhacorrida.core.update.ReleaseNotesParser
import br.com.minhacorrida.core.update.ReleaseSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckForUpdateUseCaseTest {
    private fun sourceOf(release: AppRelease?) = object : ReleaseSource {
        override fun latest() = release
    }

    @Test
    fun `versao publicada maior e oferecida`() {
        val release = AppRelease(5, "https://exemplo/app.apk")
        assertEquals(release, CheckForUpdateUseCase(sourceOf(release), 4, NoOpLogger)())
    }

    @Test
    fun `mesma versao nao e oferecida`() =
        assertNull(CheckForUpdateUseCase(sourceOf(AppRelease(4, "x")), 4, NoOpLogger)())

    @Test
    fun `falha na consulta nao derruba o app`() {
        val failing = object : ReleaseSource { override fun latest(): AppRelease? = error("sem internet") }
        assertNull(CheckForUpdateUseCase(failing, 1, NoOpLogger)())
    }

    @Test
    fun `le versionCode das notas da release`() {
        assertEquals(12, ReleaseNotesParser.versionCode("APK gerado em 30/10\nversionCode=12"))
        assertNull(ReleaseNotesParser.versionCode("sem versão"))
    }
}
