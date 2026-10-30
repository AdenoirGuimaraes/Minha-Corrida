package br.com.minhacorrida.core.update

import br.com.minhacorrida.core.port.AppLogger

/**
 * Compara a versão instalada com a última publicada.
 *
 * @param currentVersionCode versionCode do APK em execução.
 */
class CheckForUpdateUseCase(
    private val source: ReleaseSource,
    private val currentVersionCode: Int,
    private val logger: AppLogger,
) {
    /** @return a versão nova, ou null se já está atualizado ou a consulta falhou. */
    operator fun invoke(): AppRelease? =
        try {
            source.latest()?.takeIf { it.versionCode > currentVersionCode }
        } catch (e: Exception) {
            logger.warn(TAG, "Falha ao verificar atualização", e)
            null
        }

    private companion object {
        const val TAG = "CheckForUpdate"
    }
}
