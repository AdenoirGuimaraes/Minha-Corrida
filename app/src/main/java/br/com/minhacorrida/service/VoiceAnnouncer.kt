package br.com.minhacorrida.service

import android.content.Context
import android.speech.tts.TextToSpeech
import br.com.minhacorrida.core.port.AppLogger
import java.util.Locale

/**
 * Fala "Aceitar corrida…" nas corridas verdes.
 *
 * O motor de voz é preparado uma vez, quando o serviço inicia, para não atrasar o card;
 * a fala em si é assíncrona (o Android toca enquanto o código segue). Se o motor ainda
 * não estiver pronto ou não tiver português, a fala é ignorada sem afetar a leitura.
 */
class VoiceAnnouncer(context: Context, private val logger: AppLogger) {

    @Volatile private var ready = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = try {
            status == TextToSpeech.SUCCESS && configureLanguage()
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao preparar a voz", e)
            false
        }
        logger.debug(TAG, if (ready) "Voz pronta" else "Voz indisponível (status $status)")
    }

    /** Fala a frase, interrompendo a anterior (uma oferta nova tem prioridade). */
    fun speak(text: String) {
        if (!ready) {
            logger.warn(TAG, "Voz ainda não está pronta; frase ignorada")
            return
        }
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao falar", e)
        }
    }

    fun stop() = runCatching { tts.stop() }

    fun shutdown() = runCatching {
        tts.stop()
        tts.shutdown()
    }

    private fun configureLanguage(): Boolean {
        val result = tts.setLanguage(Locale("pt", "BR"))
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private companion object {
        const val TAG = "VoiceAnnouncer"
        const val UTTERANCE_ID = "minha_corrida_oferta"
    }
}
