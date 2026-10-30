package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.model.OverlayTimeout
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

    override fun getOverlayTimeoutSeconds(): Int =
        prefs.getInt(KEY_OVERLAY_TIMEOUT, OverlayTimeout.DEFAULT_SECONDS)
            .takeIf(OverlayTimeout::isValid) ?: OverlayTimeout.DEFAULT_SECONDS

    override fun setOverlayTimeoutSeconds(seconds: Int) {
        require(OverlayTimeout.isValid(seconds)) {
            "Tempo deve ficar entre ${OverlayTimeout.MIN_SECONDS} e ${OverlayTimeout.MAX_SECONDS} segundos"
        }
        prefs.edit().putInt(KEY_OVERLAY_TIMEOUT, seconds).apply()
    }

    private companion object {
        const val PREFS_NAME = "minha_corrida_prefs"
        const val KEY_MIN_VALUE_PER_KM = "min_value_per_km"
        const val KEY_OVERLAY_TIMEOUT = "overlay_timeout_seconds"
        val DEFAULT_MIN_VALUE_PER_KM = BigDecimal("1.80")
    }
}
