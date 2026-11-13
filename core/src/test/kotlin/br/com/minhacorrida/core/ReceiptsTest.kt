package br.com.minhacorrida.core

import br.com.minhacorrida.core.backup.BackupPolicy
import br.com.minhacorrida.core.receipt.MaintenanceEntry
import br.com.minhacorrida.core.receipt.ReceiptImage
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pedido de 13/11 (item 12): foto do comprovante no abastecimento e na manutenção. */
class ReceiptsTest {
    @Test
    fun `foto reduzida mantendo a proporcao e leitura economica`() {
        assertEquals(1600 to 1200, ReceiptImage.scaledSize(4000, 3000))
        assertEquals(900 to 1600, ReceiptImage.scaledSize(2250, 4000))
        assertEquals(800 to 600, ReceiptImage.scaledSize(800, 600))          // pequena: fica igual
        assertEquals(2, ReceiptImage.sampleSize(4000, 3000))                  // 2000 px ainda ≥ 1600
        assertEquals(1, ReceiptImage.sampleSize(3000, 2000))
    }

    @Test
    fun `nome do arquivo seguro para o backup`() {
        val id = "3f2a9c1e-77b0-4c1d-9a51-0e6f2d1b8c33"
        assertEquals("$id.jpg", ReceiptImage.fileName(id))
        assertTrue(BackupPolicy.canRestoreReceipt("$id.jpg"))
        assertFalse(BackupPolicy.canRestoreReceipt("../historico_corridas.json"))
        assertFalse(BackupPolicy.canRestoreReceipt("foto.png"))
        assertTrue(BackupPolicy.canRestorePreference("minha_corrida_maintenance_log"))
    }

    @Test
    fun `manutencao precisa de descricao e valor`() {
        val ok = MaintenanceEntry("m1", LocalDate.of(2026, 11, 13), "Troca de óleo", BigDecimal("180.00"))
        assertEquals("Troca de óleo", ok.description)
        assertFailsWith<IllegalArgumentException> { MaintenanceEntry("m2", LocalDate.now(), " ", BigDecimal("10")) }
        assertFailsWith<IllegalArgumentException> { MaintenanceEntry("m3", LocalDate.now(), "Pneu", BigDecimal.ZERO) }
    }
}
