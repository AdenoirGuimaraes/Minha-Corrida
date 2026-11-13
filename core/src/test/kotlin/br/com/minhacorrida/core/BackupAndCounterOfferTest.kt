package br.com.minhacorrida.core

import br.com.minhacorrida.core.backup.BackupPolicy
import br.com.minhacorrida.core.backup.BackupSummary
import br.com.minhacorrida.core.format.CounterOffer
import br.com.minhacorrida.core.model.Platform
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pedidos de 13/11: backup do histórico (item 16) e Mín. verde arredondado no inDrive (item 4). */
class BackupAndCounterOfferTest {
    @Test
    fun `nome do arquivo e conferencia do backup`() {
        assertEquals("MinhaCorrida-backup-2026-11-13_0104.json", BackupPolicy.fileName(LocalDateTime.of(2026, 11, 13, 1, 4)))
        assertEquals(BackupPolicy.Check.OK, BackupPolicy.check("minha-corrida-backup", 1))
        assertEquals(BackupPolicy.Check.NOT_A_BACKUP, BackupPolicy.check("outra-coisa", 1))
        assertEquals(BackupPolicy.Check.NOT_A_BACKUP, BackupPolicy.check(null, 1))
        assertEquals(BackupPolicy.Check.NEWER_VERSION, BackupPolicy.check("minha-corrida-backup", 2))
    }

    @Test
    fun `so restaura o que esta na lista e nao leva o estado do momento`() {
        assertTrue(BackupPolicy.canRestoreFile("historico_corridas.json"))
        assertTrue(BackupPolicy.canRestorePreference("minha_corrida_fuel_log"))
        assertFalse(BackupPolicy.canRestorePreference("minha_corrida_session"))       // leitura pausada
        assertFalse(BackupPolicy.canRestorePreference("minha_corrida_current_trip"))  // trajeto em andamento
        assertFalse(BackupPolicy.canRestoreFile("../../outro.json"))
    }

    @Test
    fun `resumo para confirmar a restauracao`() {
        assertEquals(
            "Backup de 13/11/2026 01:04 (versão 0.2.50): 120 corrida(s), 5 abastecimento(s), 40 trajeto(s).",
            BackupSummary(LocalDateTime.of(2026, 11, 13, 1, 4), "0.2.50", 120, 5, 40).describe(),
        )
    }

    @Test
    fun `inDrive mostra o Min verde arredondado para cima e Uber o exato`() {
        assertEquals("14.00", CounterOffer.displayGreenFare(BigDecimal("13.42"), Platform.INDRIVE).toPlainString())
        assertEquals("13.00", CounterOffer.displayGreenFare(BigDecimal("13.00"), Platform.INDRIVE).toPlainString())
        assertEquals("13.42", CounterOffer.displayGreenFare(BigDecimal("13.42"), Platform.UBER).toPlainString())
    }
}
