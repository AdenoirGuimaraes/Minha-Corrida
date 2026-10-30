package br.com.minhacorrida.core.port

/**
 * Abstração de log. Mantém o núcleo independente do Android (DIP):
 * o app injeta uma implementação baseada em android.util.Log.
 */
interface AppLogger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)
}

/** Logger silencioso, útil em testes. */
object NoOpLogger : AppLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, error: Throwable?) = Unit
    override fun error(tag: String, message: String, error: Throwable?) = Unit
}
