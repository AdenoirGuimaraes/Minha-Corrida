package br.com.minhacorrida.core.receipt

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Manutenção do carro (troca de óleo, pneu, revisão…) com valor, para guardar o comprovante
 * junto (item 12 do backlog). Não muda o custo por km do card (esse vem das Configurações).
 */
data class MaintenanceEntry(
    val id: String,
    val date: LocalDate,
    val description: String,
    val value: BigDecimal,
) {
    init {
        require(description.isNotBlank()) { "Descreva a manutenção" }
        require(value.signum() > 0) { "Valor deve ser positivo" }
    }
}

interface MaintenanceLogRepository {
    /** Todas, da mais recente para a mais antiga. */
    fun all(): List<MaintenanceEntry>
    fun save(entry: MaintenanceEntry)
    fun delete(id: String)
}

/** Fotos de comprovante (cupom do posto, nota da oficina), uma por registro (id do registro). */
interface ReceiptStore {
    fun has(entryId: String): Boolean

    companion object {
        /** Sem fotos (testes, relatório sem comprovantes). */
        val NONE = object : ReceiptStore {
            override fun has(entryId: String) = false
        }
    }
}

/**
 * Regras da foto: lado maior de no máximo [MAX_SIDE] px (legível e leve: ~150–300 KB em JPEG),
 * nome do arquivo = id do registro + ".jpg" (só letras, números e hífen — nada de caminhos).
 */
object ReceiptImage {
    const val MAX_SIDE = 1600
    const val JPEG_QUALITY = 75
    private val SAFE_NAME = Regex("^[A-Za-z0-9-]{1,64}\\.jpg$")

    fun fileName(entryId: String): String = "$entryId.jpg"

    fun isSafeName(name: String) = SAFE_NAME.matches(name)

    /** Tamanho final mantendo a proporção; foto menor que o limite fica como está. */
    fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Tamanho inválido" }
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val factor = maxSide.toDouble() / longest
        return Math.round(width * factor).toInt().coerceAtLeast(1) to Math.round(height * factor).toInt().coerceAtLeast(1)
    }

    /** Fator de redução na leitura (potência de 2) para não abrir a foto inteira na memória. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
