package br.com.minhacorrida.infra

import android.util.Log
import br.com.minhacorrida.BuildConfig
import br.com.minhacorrida.core.port.AppLogger

/** Implementação de log com Logcat. Debug só aparece em builds de desenvolvimento. */
class AndroidLogger : AppLogger {
    override fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, message)
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        Log.w(PREFIX + tag, message, error)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        Log.e(PREFIX + tag, message, error)
    }

    private companion object {
        const val PREFIX = "MC/"
    }
}
