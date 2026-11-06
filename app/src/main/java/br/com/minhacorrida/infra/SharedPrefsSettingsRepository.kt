package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.OverlayTimeout
import br.com.minhacorrida.core.model.Platform
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

    /** Custos guardados como texto (BigDecimal sem perda); qualquer valor inválido = não informado. */
    override fun getOperatingCost(): OperatingCost? {
        val fuel = prefs.getString(KEY_FUEL_PRICE, null)?.toBigDecimalOrNull() ?: return null
        val kmPerLiter = prefs.getString(KEY_KM_PER_LITER, null)?.toBigDecimalOrNull() ?: return null
        val other = prefs.getString(KEY_OTHER_COST, null)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return runCatching { OperatingCost(fuel, kmPerLiter, other) }.getOrNull()
    }

    override fun setOperatingCost(cost: OperatingCost?) {
        val editor = prefs.edit()
        if (cost == null) {
            editor.remove(KEY_FUEL_PRICE).remove(KEY_KM_PER_LITER).remove(KEY_OTHER_COST)
        } else {
            editor.putString(KEY_FUEL_PRICE, cost.fuelPricePerLiter.toPlainString())
                .putString(KEY_KM_PER_LITER, cost.kmPerLiter.toPlainString())
                .putString(KEY_OTHER_COST, cost.otherCostPerKm.toPlainString())
        }
        editor.apply()
    }

    override fun getMinPassengerRating(): BigDecimal? =
        prefs.getString(KEY_MIN_RATING, null)?.toBigDecimalOrNull()

    override fun setMinPassengerRating(rating: BigDecimal?) {
        if (rating == null) {
            prefs.edit().remove(KEY_MIN_RATING).apply()
            return
        }
        require(rating >= BigDecimal.ONE && rating <= BigDecimal(5)) { "Nota mínima deve ficar entre 1 e 5" }
        prefs.edit().putString(KEY_MIN_RATING, rating.toPlainString()).apply()
    }

    override fun getEnabledPlatforms(): Set<Platform> {
        val names = prefs.getStringSet(KEY_PLATFORMS, null) ?: return Platform.entries.toSet()
        return names.mapNotNull { name -> Platform.entries.firstOrNull { it.name == name } }.toSet()
    }

    override fun setEnabledPlatforms(platforms: Set<Platform>) {
        prefs.edit().putStringSet(KEY_PLATFORMS, platforms.map { it.name }.toSet()).apply()
    }

    override fun getInDriveFeePercent(): BigDecimal =
        prefs.getString(KEY_INDRIVE_FEE, null)?.toBigDecimalOrNull() ?: DEFAULT_INDRIVE_FEE

    override fun setInDriveFeePercent(percent: BigDecimal) {
        require(percent.signum() >= 0 && percent <= MAX_FEE) { "Taxa do inDrive deve ficar entre 0 e 50%" }
        prefs.edit().putString(KEY_INDRIVE_FEE, percent.toPlainString()).apply()
    }

    override fun isVoiceEnabled(): Boolean = prefs.getBoolean(KEY_VOICE, true)

    override fun setVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "minha_corrida_prefs"
        const val KEY_MIN_VALUE_PER_KM = "min_value_per_km"
        const val KEY_OVERLAY_TIMEOUT = "overlay_timeout_seconds"
        const val KEY_FUEL_PRICE = "fuel_price_per_liter"
        const val KEY_KM_PER_LITER = "km_per_liter"
        const val KEY_OTHER_COST = "other_cost_per_km"
        const val KEY_MIN_RATING = "min_passenger_rating"
        const val KEY_PLATFORMS = "enabled_platforms"
        const val KEY_VOICE = "voice_enabled"
        const val KEY_INDRIVE_FEE = "indrive_fee_percent"
        val DEFAULT_INDRIVE_FEE = BigDecimal("10")
        val MAX_FEE = BigDecimal("50")
        val DEFAULT_MIN_VALUE_PER_KM = BigDecimal("1.80")
    }
}
