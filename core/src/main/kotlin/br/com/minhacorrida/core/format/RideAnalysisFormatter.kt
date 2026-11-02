package br.com.minhacorrida.core.format

import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.RideAnalysis
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Textos do card de 3 quadrados (R$/km | Lucro | Mínimo) com a faixa do app no topo.
 * Os números grandes vêm sem "R$": o rótulo pequeno de cada quadrado diz o que são.
 */
data class RideCardText(
    val platform: String,
    val rating: String?,
    val valuePerKm: String,
    val kmDetails: String,
    val profitLabel: String,
    val profitValue: String,
    val profitDetails: String,
    val minimumValue: String,
    val minimumLabel: String,
)

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

    /** Ex.: "Mín. p/ R$ 2,00/km: R$ 10,40". */
    fun minimumFare(analysis: RideAnalysis): String =
        "Mín. p/ R$ ${money(analysis.minimum.minValuePerKm)}/km: R$ ${money(analysis.minimum.minimumFare)}"

    /**
     * Textos do card. Ex. (Uber, R$ 7,37 em 2,8 + 3,5 km):
     * R$/km "1,17" / "6,3 km\n(2,8 + 3,5)"; Lucro "1,62" / "Corrida R$ 7,37\nGasto R$ 5,75";
     * Mínimo "12,60" / "Mín. p/\nR$ 2,00/km".
     */
    fun card(analysis: RideAnalysis): RideCardText {
        val offer = analysis.offer
        val fare = "Corrida R$ ${money(offer.fare)}"
        val pickup = offer.pickupKm
        val kmDetails = if (pickup != null) {
            "${km(analysis.metrics.totalKm)} km\n(${km(pickup)} + ${km(offer.tripKm)})"
        } else {
            "${km(offer.tripKm)} km\n(só trajeto)"
        }
        val profit = analysis.profit
        return RideCardText(
            platform = offer.platform.displayName,
            rating = offer.passengerRating?.let { rating ->
                val text = "★ ${DecimalFormat("0.00", DecimalFormatSymbols(locale)).format(rating)}"
                if (analysis.lowPassengerRating) "$text abaixo do mín." else text
            },
            valuePerKm = money(analysis.metrics.valuePerKm),
            kmDetails = kmDetails,
            profitLabel = if (profit != null && profit.netProfit.signum() < 0) "Prejuízo" else "Lucro",
            profitValue = profit?.let { money(it.netProfit.abs()) } ?: "—",
            profitDetails = profit?.let { "$fare\nGasto R$ ${money(it.cost)}" } ?: "$fare\nInforme os custos",
            minimumValue = money(analysis.minimum.minimumFare),
            minimumLabel = minimumLabel(analysis),
        )
    }

    /**
     * Texto de baixo do 3º quadrado: o lucro se a corrida pagasse o valor mínimo.
     * Ex.: "Lucro no mínimo\nR$ 7,03". Sem custos informados: "Mín. p/\nR$ 2,00/km".
     */
    private fun minimumLabel(analysis: RideAnalysis): String {
        val profit = analysis.profitAtMinimumFare
            ?: return "Mín. p/\nR$ ${money(analysis.minimum.minValuePerKm)}/km"
        val label = if (profit.signum() < 0) "Prejuízo no mínimo" else "Lucro no mínimo"
        return "$label\nR$ ${money(profit.abs())}"
    }

    /**
     * Ex.: "Lucro: R$ 6,40 (gasto R$ 4,60)" ou "Prejuízo: R$ 1,20 (gasto R$ 9,20)".
     * @return null quando os custos do carro não foram informados.
     */
    fun profit(analysis: RideAnalysis): String? {
        val profit = analysis.profit ?: return null
        val label = if (profit.netProfit.signum() < 0) "Prejuízo" else "Lucro"
        return "$label: R$ ${money(profit.netProfit.abs())} (gasto R$ ${money(profit.cost)})"
    }

    /**
     * Ex.: "Nota 4,82" ou "Nota 4,50 — abaixo do mínimo".
     * @return null quando a tela não mostra a nota (passageiro novo).
     */
    fun passengerRating(analysis: RideAnalysis): String? {
        val rating = analysis.offer.passengerRating ?: return null
        val text = "Nota ${DecimalFormat("0.00", DecimalFormatSymbols(locale)).format(rating)}"
        return if (analysis.lowPassengerRating) "$text — abaixo do mínimo" else text
    }

    /**
     * Resumo da tela de configurações: custo por km e quanto sobra com o mínimo escolhido.
     * Ex.: "Seu custo: R$ 0,63/km. Com o mínimo de R$ 2,00/km, sobram R$ 1,37 de lucro por km."
     */
    fun costSummary(cost: OperatingCost, minValuePerKm: BigDecimal): String {
        val costPerKm = cost.costPerKm.setScale(2, RoundingMode.HALF_UP)
        val margin = minValuePerKm - costPerKm
        val base = "Seu custo: R$ ${money(costPerKm)}/km."
        return if (margin.signum() > 0) {
            "$base Com o mínimo de R$ ${money(minValuePerKm)}/km, sobram R$ ${money(margin)} de lucro por km."
        } else {
            "$base Atenção: o mínimo de R$ ${money(minValuePerKm)}/km não cobre o custo — dá prejuízo."
        }
    }

    /** Frase falada nas corridas verdes. Ex.: "Aceitar corrida. 8 reais e 50 centavos. inDrive". */
    fun speech(analysis: RideAnalysis): String =
        "Aceitar corrida. ${spokenMoney(analysis.offer.fare)}. ${analysis.offer.platform.displayName}"

    /** "1 real", "11 reais", "8 reais e 50 centavos", "70 centavos". */
    internal fun spokenMoney(value: BigDecimal): String {
        val cents = value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigInteger().toLong()
        val reais = cents / 100
        val centavos = cents % 100
        val reaisText = if (reais == 1L) "1 real" else "$reais reais"
        val centavosText = if (centavos == 1L) "1 centavo" else "$centavos centavos"
        return when {
            centavos == 0L -> reaisText
            reais == 0L -> centavosText
            else -> "$reaisText e $centavosText"
        }
    }

    // DecimalFormat não é thread-safe: cria uma instância por chamada.
    private fun money(value: BigDecimal) = DecimalFormat("#,##0.00", DecimalFormatSymbols(locale)).format(value)
    private fun km(value: BigDecimal) = DecimalFormat("#,##0.#", DecimalFormatSymbols(locale)).format(value)
}
