package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.km.CommuteChoiceRepository
import java.time.LocalDate

/** Check de cada dia "descontar o deslocamento" (chave "2026-11-12" → true). Padrão: desmarcado. */
class SharedPrefsCommuteChoiceRepository(context: Context) : CommuteChoiceRepository {

    private val prefs = context.applicationContext.getSharedPreferences("minha_corrida_commute_days", Context.MODE_PRIVATE)

    override fun isIncluded(date: LocalDate): Boolean = prefs.getBoolean(date.toString(), false)

    override fun setIncluded(date: LocalDate, included: Boolean) {
        val editor = prefs.edit()
        if (included) editor.putBoolean(date.toString(), true) else editor.remove(date.toString())
        // Mesma retenção do histórico (60 dias).
        val limit = date.minusDays(RETENTION_DAYS)
        prefs.all.keys.filter { runCatching { LocalDate.parse(it) < limit }.getOrDefault(false) }.forEach(editor::remove)
        editor.apply()
    }

    private companion object {
        const val RETENTION_DAYS = 60L
    }
}
