package br.com.minhacorrida.infra

import android.content.Context
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.ReadingSession

/**
 * Guarda se a leitura está ligada. SharedPreferences fica em memória após a 1ª leitura,
 * então o serviço pode consultar a cada evento de tela sem custo.
 * O padrão é ligada: quem ativou a acessibilidade e nunca tocou em Encerrar segue lendo.
 */
class SharedPrefsReadingSession(context: Context, private val logger: AppLogger) : ReadingSession {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isActive(): Boolean =
        try {
            prefs.getBoolean(KEY_ACTIVE, true) && !prefs.getBoolean(KEY_PAUSED, false)
        } catch (e: Exception) {
            logger.warn(TAG, "Falha ao ler o estado da leitura; mantendo ligada", e)
            true
        }

    override fun isPausedByUser(): Boolean = runCatching { prefs.getBoolean(KEY_PAUSED, false) }.getOrDefault(false)

    override fun setPausedByUser(paused: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_PAUSED, paused).commit()
            logger.debug(TAG, if (paused) "Leitura pausada pelo motorista" else "Leitura religada pelo motorista")
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao gravar a pausa da leitura", e)
        }
    }

    override fun start() = save(true)

    override fun stop() = save(false)

    private fun save(active: Boolean) {
        try {
            // commit (síncrono): o serviço precisa ver a mudança antes de o app fechar.
            prefs.edit().putBoolean(KEY_ACTIVE, active).commit()
            logger.debug(TAG, if (active) "Leitura ligada" else "Leitura encerrada")
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao gravar o estado da leitura", e)
        }
    }

    private companion object {
        const val TAG = "ReadingSession"
        const val PREFS_NAME = "minha_corrida_session"
        const val KEY_ACTIVE = "reading_active"
        const val KEY_PAUSED = "reading_paused_by_user"
    }
}
