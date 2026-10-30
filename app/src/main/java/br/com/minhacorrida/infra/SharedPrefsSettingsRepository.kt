package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.port.SettingsRepository
import java.math.BigDecimal

/** Persiste as preferências em SharedPreferences (leitura em memória, rápida). */
class SharedPrefsSettingsRepository(context: Context) : SettingsRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getMinValuePerKm(): BigDecimal =
        prefs.getString(KEY_MIN_VALUE_PER_KM, null)?.toBigDecimalOrNull() ?: DEFAULT_MIN_VALUE_PER_KM

    override fun setMinValuePerKm(value: BigDecimal) {
        require(value.signum() > 0) { "Mínimo por km deve ser positivo" }
        prefs.edit().putString(KEY_MIN_VALUE_PER_KM, value.toPlainString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "minha_corrida_prefs"
        const val KEY_MIN_VALUE_PER_KM = "min_value_per_km"
        val DEFAULT_MIN_VALUE_PER_KM = BigDecimal("1.80")
    }
}
