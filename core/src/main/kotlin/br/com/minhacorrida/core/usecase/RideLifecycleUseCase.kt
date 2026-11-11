package br.com.minhacorrida.core.usecase

import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.history.RideHistoryRepository
import br.com.minhacorrida.core.history.RideRecord
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.SettingsRepository
import java.math.BigDecimal

/** O que uma tela do app de corrida mudou no histórico. */
sealed class RideLifecycleEvent {
    /** Corrida confirmada (o passageiro é seu): guardada na aba Corridas. */
    data class Confirmed(val record: RideRecord) : RideLifecycleEvent()

    /** Confirmação vista, mas sem pedido lido antes (sem km): nada guardado. */
    data class ConfirmedWithoutOffer(val platform: Platform) : RideLifecycleEvent()

    /** Corrida terminou (tela de avaliação): marcada como realizada. */
    data class Completed(val recordId: String) : RideLifecycleEvent()

    /** inDrive: contraproposta enviada, aguardando o passageiro. */
    data class CounterOfferSent(val fare: BigDecimal) : RideLifecycleEvent()
}

/**
 * Grava no histórico só as corridas que o motorista REALMENTE pegou, seguindo as telas
 * de cada app (calibrado com os relatórios de 09/11). Nada é gravado pela cor do card
 * nem pelo toque — os toques no cartão da Uber nem chegam ao app.
 *
 * | Etapa       | Uber (textos da tela)                    | inDrive (textos da tela)                                  |
 * |-------------|------------------------------------------|-----------------------------------------------------------|
 * | Pedido      | cartão lido (OCR)                        | "Pedido de viagem" lido                                    |
 * | Aguardando  | —                                        | "Oferecendo sua tarifa R$ 13. Aguarde a resposta"          |
 * | Confirmada  | "Encontro com Fernanda" / "Destino de …" | "R$ 13 · Dinheiro" + "Estou aqui"/"Começar a corrida"/… |
 * | Concluída   | "Como foi a viagem?" / "Avaliar usuário" | "Avalie seu passageiro"                                    |
 *
 * Na confirmação, a corrida guardada é o ÚLTIMO pedido lido daquele app (km da busca e da
 * viagem); no inDrive o valor é o da tela da corrida ("R$ 13 · Dinheiro"), que já inclui
 * contraproposta feita pelo valor pronto ou pelo lápis. Guarda de qualquer cor: foi o
 * motorista que aceitou. Enquanto uma corrida está em andamento, as telas repetidas dela
 * não gravam de novo; ofertas novas durante a corrida não são gravadas.
 */
class RideLifecycleUseCase(
    private val evaluate: EvaluateRideUseCase,
    private val settings: SettingsRepository,
    private val record: RecordRideUseCase,
    private val repository: RideHistoryRepository,
    private val logger: AppLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Seen(val analysis: RideAnalysis, val atMillis: Long)
    private data class Active(val recordId: String?, val key: String, val sinceMillis: Long)

    private val lastOffer = mutableMapOf<Platform, Seen>()
    private val active = mutableMapOf<Platform, Active>()

    /** Pedido lido na tela (card mostrado). Durante uma corrida, também é guardado: pode ser a próxima. */
    @Synchronized
    fun onOffer(analysis: RideAnalysis) {
        lastOffer[analysis.offer.platform] = Seen(analysis, clock())
    }

    /**
     * Uma leitura da tela do app (acessibilidade + OCR).
     * @return o que mudou no histórico, ou null se nada.
     */
    @Synchronized
    fun onScreen(platform: Platform, texts: List<String>): RideLifecycleEvent? = try {
        expireStaleRide(platform)
        val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }
        when (platform) {
            Platform.UBER -> uberScreen(lines)
            Platform.INDRIVE -> inDriveScreen(lines)
            else -> null
        }
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao acompanhar a corrida ($platform)", e)
        null
    }

    // ---- Uber ----

    private fun uberScreen(lines: List<String>): RideLifecycleEvent? {
        if (lines.any { UBER_DONE.containsMatchIn(it) }) return complete(Platform.UBER)
        // "Encontro com Fernanda" (indo buscar) ou "Destino de Fernanda" (em viagem).
        val passenger = lines.firstNotNullOfOrNull { UBER_RIDE.find(it)?.groupValues?.get(2)?.trim() }
            ?: return null
        val current = active[Platform.UBER]
        if (current != null && current.key.equals(passenger, ignoreCase = true)) return null
        return confirm(Platform.UBER, key = passenger, fare = null)
    }

    // ---- inDrive ----

    private fun inDriveScreen(lines: List<String>): RideLifecycleEvent? {
        if (lines.any { INDRIVE_DONE.containsMatchIn(it) }) return complete(Platform.INDRIVE)

        lines.firstNotNullOfOrNull { INDRIVE_WAITING.find(it) }?.let { match ->
            return NumberParser.parse(match.groupValues[1])?.let { RideLifecycleEvent.CounterOfferSent(it) }
        }

        val inRide = lines.any { line -> INDRIVE_RIDE_MARKERS.any { line.startsWith(it, ignoreCase = true) } }
        val fareLine = lines.firstOrNull { INDRIVE_RIDE_FARE.containsMatchIn(it) }
        if (!inRide || fareLine == null) return null
        if (active[Platform.INDRIVE] != null) return null // mesma corrida em andamento
        val fare = NumberParser.parse(INDRIVE_RIDE_FARE.find(fareLine)!!.groupValues[1]) ?: return null
        return confirm(Platform.INDRIVE, key = fareLine, fare = fare)
    }

    // ---- Comum ----

    /** Guarda o último pedido daquele app (com o valor final, no inDrive) como corrida aceita. */
    private fun confirm(platform: Platform, key: String, fare: BigDecimal?): RideLifecycleEvent {
        val now = clock()
        val seen = lastOffer[platform]?.takeIf { now - it.atMillis <= OFFER_MAX_AGE_MS }
        if (seen == null) {
            // Sem km não há como calcular: marca como ativa para não insistir a cada leitura.
            active[platform] = Active(null, key, now)
            logger.warn(TAG, "$platform: corrida confirmada sem pedido lido antes; não guardada")
            return RideLifecycleEvent.ConfirmedWithoutOffer(platform)
        }
        val original = seen.analysis
        val analysis = if (fare != null && fare.compareTo(original.offer.fare) != 0) {
            evaluate(original.offer.copy(fare = fare), currentRules())
        } else {
            original
        }
        val saved = record(
            analysis,
            originalFare = original.offer.fare.takeIf { fare != null && fare.compareTo(it) != 0 },
            acceptedByDriver = true,
        )
        lastOffer.remove(platform)
        active[platform] = Active(saved?.id, key, now)
        logger.debug(TAG, "$platform: corrida confirmada ($key) R$ ${analysis.offer.fare}")
        return if (saved != null) RideLifecycleEvent.Confirmed(saved) else RideLifecycleEvent.ConfirmedWithoutOffer(platform)
    }

    /** Tela de avaliação: a corrida em andamento vira realizada. */
    private fun complete(platform: Platform): RideLifecycleEvent? {
        val ride = active.remove(platform) ?: return null
        val id = ride.recordId ?: return null
        repository.setDone(id, true)
        logger.debug(TAG, "$platform: corrida concluída ($id)")
        return RideLifecycleEvent.Completed(id)
    }

    /** Corrida "em andamento" há horas (fim não visto): libera para a próxima. */
    private fun expireStaleRide(platform: Platform) {
        val ride = active[platform] ?: return
        if (clock() - ride.sinceMillis > ACTIVE_MAX_AGE_MS) active.remove(platform)
    }

    private fun currentRules() = RideRules.from(settings)

    private companion object {
        const val TAG = "RideLifecycle"

        /** Pedido lido até 10 min antes da confirmação (inDrive: o passageiro pode demorar). */
        const val OFFER_MAX_AGE_MS = 10 * 60_000L
        const val ACTIVE_MAX_AGE_MS = 3 * 60 * 60_000L

        // Uber (relatório de 09/11 18:49–19:00). OCR às vezes junta lixo antes ("Oo Encontro com").
        val UBER_RIDE = Regex("""(?i)(^|\s)(?:encontro com|destino de)\s+(\p{L}[\p{L} .'-]{0,40})$""")
        val UBER_DONE = Regex("""(?i)^(como foi a viagem\??|avaliar usu[aá]rio)$""")

        // inDrive (relatório de 09/11 18:26–18:43).
        val INDRIVE_WAITING = Regex("""(?i)oferecendo sua tarifa\s*R\$\s*(\d+(?:[.,]\d{1,2})?)""")
        val INDRIVE_RIDE_FARE = Regex("""^R\$\s*(\d+(?:[.,]\d{1,2})?)\s*·""")
        val INDRIVE_RIDE_MARKERS = listOf("Estou aqui", "Começar a corrida", "Concluir corrida", "Embarque em", "Chegada em")
        val INDRIVE_DONE = Regex("""(?i)^avalie seu passageiro$""")
    }
}
