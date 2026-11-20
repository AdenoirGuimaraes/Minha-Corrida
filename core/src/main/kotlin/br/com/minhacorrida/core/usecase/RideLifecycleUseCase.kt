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
 *
 * Uber (19/11, corrida das 20:23): a oferta aceita (R$ 14,01) não foi lida e o app gravou uma
 * oferta recusada minutos antes (R$ 18,08, 13,1 km). Na Uber a oferta dura ~15 s e a confirmação
 * vem logo após o aceite, então só vale o pedido lido no último minuto ([offerMaxAge]).
 * Corrida em dinheiro (19/11): a Uber termina em "Quanto você recebeu em dinheiro?" /
 * "Concluir pagamento" e a avaliação só aparece como toque ("Avaliar usuário") — ambos concluem.
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

    /** Última contraproposta avisada por app: a tela "aguardando" se repete a cada segundo. */
    private val lastCounter = mutableMapOf<Platform, BigDecimal>()

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

    /**
     * Toque num botão do app (ex.: "Avaliar usuário" na Uber, que não aparece como tela na corrida
     * em dinheiro). Só serve para concluir a corrida em andamento; nunca grava corrida nova.
     * @return [RideLifecycleEvent.Completed] se o toque encerrou a corrida, ou null.
     */
    @Synchronized
    fun onTap(platform: Platform, text: String): RideLifecycleEvent? = try {
        val t = text.trim()
        val done = when (platform) {
            Platform.UBER -> isUberEnd(t)
            Platform.INDRIVE -> INDRIVE_DONE.containsMatchIn(t)
            else -> false
        }
        if (done && t.isNotEmpty()) complete(platform) else null
    } catch (e: Exception) {
        logger.error(TAG, "Falha ao tratar toque ($platform)", e)
        null
    }

    // ---- Uber ----

    private fun isUberEnd(line: String) = UBER_DONE.containsMatchIn(line) || UBER_CASH_DONE.containsMatchIn(line)

    private fun uberScreen(lines: List<String>): RideLifecycleEvent? {
        if (lines.any { isUberEnd(it) }) return complete(Platform.UBER)
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
            val fare = NumberParser.parse(match.groupValues[1]) ?: return null
            // Mesma contraproposta na tela de novo: já avisada (o relatório enchia de repetições).
            if (lastCounter[Platform.INDRIVE]?.compareTo(fare) == 0) return null
            lastCounter[Platform.INDRIVE] = fare
            return RideLifecycleEvent.CounterOfferSent(fare)
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
        lastCounter.remove(platform)
        val seen = lastOffer[platform]?.takeIf { now - it.atMillis <= offerMaxAge(platform) }
        if (seen == null) {
            // Sem km não há como calcular: marca como ativa para não insistir a cada leitura.
            active[platform] = Active(null, key, now)
            logger.warn(TAG, "$platform: corrida confirmada sem pedido lido no prazo; não guardada")
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

    /** Prazo entre o pedido lido e a confirmação: Uber 1 min; inDrive 10 min (o passageiro demora). */
    private fun offerMaxAge(platform: Platform): Long =
        if (platform == Platform.UBER) UBER_OFFER_MAX_AGE_MS else OFFER_MAX_AGE_MS

    private companion object {
        const val TAG = "RideLifecycle"

        /** Pedido lido até 10 min antes da confirmação (inDrive: o passageiro pode demorar). */
        const val OFFER_MAX_AGE_MS = 10 * 60_000L

        /** Uber: a oferta dura ~15 s e "Encontro com" vem logo após o aceite (corrida de 19/11 20:23). */
        const val UBER_OFFER_MAX_AGE_MS = 60_000L
        const val ACTIVE_MAX_AGE_MS = 3 * 60 * 60_000L

        // Uber (relatório de 09/11 18:49–19:00). OCR às vezes junta lixo antes ("Oo Encontro com").
        val UBER_RIDE = Regex("""(?i)(^|\s)(?:encontro com|destino de)\s+(\p{L}[\p{L} .'-]{0,40})$""")
        // Fim da corrida na Uber. Sem âncoras (pedido de 16/11): o OCR junta lixo antes/depois e a
        // frase pode vir completa ("Como foi a viagem com Fernanda?"), e aí a voz da meta não falava.
        // Corrida em dinheiro (19/11 21:00): "Quanto você recebeu em dinheiro?" → "Concluir pagamento".
        // "Coletar pagamento" aparece DURANTE a viagem e não conta.
        val UBER_CASH_DONE = Regex("""(?i)(concluir pagamento|quanto voc[eê] recebeu|recolher o pagamento em dinheiro)""")
        val UBER_DONE = Regex("""(?i)(como foi (a |sua )?viagem|avalia?r? (o |a )?(usu[aá]rio|passageir[oa])|avalie (o |a )?(usu[aá]rio|passageir[oa]))""")

        // inDrive (relatório de 09/11 18:26–18:43).
        val INDRIVE_WAITING = Regex("""(?i)oferecendo sua tarifa\s*R\$\s*(\d+(?:[.,]\d{1,2})?)""")
        val INDRIVE_RIDE_FARE = Regex("""^R\$\s*(\d+(?:[.,]\d{1,2})?)\s*·""")
        val INDRIVE_RIDE_MARKERS = listOf("Estou aqui", "Começar a corrida", "Concluir corrida", "Embarque em", "Chegada em")
        val INDRIVE_DONE = Regex("""(?i)^avalie seu passageiro$""")
    }
}
