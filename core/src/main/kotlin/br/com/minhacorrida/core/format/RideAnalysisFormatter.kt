package br.com.minhacorrida.core.format

import br.com.minhacorrida.core.model.RideAnalysis
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Formata a análise para exibição em pt-BR. */
class RideAnalysisFormatter(private val locale: Locale = Locale("pt", "BR")) {

    /** Ex.: "R$ 1,00/km". */
    fun headline(analysis: RideAnalysis): String =
        "R$ ${money(analysis.metrics.valuePerKm)}/km"

    /** Ex.: "8,9 km (2,3 + 6,6) • R$ 8,91" ou "7 km (só trajeto) • R$ 10,00". */
    fun details(analysis: RideAnalysis): String {
        val offer = analysis.offer
        val fare = "R$ ${money(offer.fare)}"
        val pickup = offer.pickupKm
        return if (pickup != null) {
            "${km(analysis.metrics.totalKm)} km (${km(pickup)} + ${km(offer.tripKm)}) • $fare"
        } else {
            "${km(offer.tripKm)} km (só trajeto) • $fare"
        }
    }

    // DecimalFormat não é thread-safe: cria uma instância por chamada.
    private fun money(value: BigDecimal) = DecimalFormat("#,##0.00", DecimalFormatSymbols(locale)).format(value)
    private fun km(value: BigDecimal) = DecimalFormat("#,##0.#", DecimalFormatSymbols(locale)).format(value)
}
