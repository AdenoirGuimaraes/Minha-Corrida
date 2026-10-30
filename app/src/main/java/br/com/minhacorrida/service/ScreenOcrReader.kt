package br.com.minhacorrida.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import br.com.minhacorrida.core.port.AppLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Lê texto desenhado como imagem (ex.: bolhas do mapa do inDrive):
 * print pela Acessibilidade (Android 11+, sem pedir permissão extra) + OCR
 * do ML Kit no próprio aparelho, apenas na faixa do mapa.
 */
class ScreenOcrReader(
    private val service: AccessibilityService,
    private val logger: AppLogger,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * @param regionBottomPx limite inferior da área lida (topo do cartão da oferta).
     * @param onResult linhas reconhecidas (vazia em caso de falha); chamado uma única vez.
     */
    fun read(regionBottomPx: Int, onResult: (List<String>) -> Unit) {
        val once = OnceCallback(onResult)
        if (!isSupported) {
            once(emptyList())
            return
        }
        try {
            takeScreenshot(regionBottomPx, once)
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao capturar a tela", e)
            once(emptyList())
        }
    }

    fun close() = runCatching { recognizer.close() }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshot(regionBottomPx: Int, onResult: OnceCallback) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    recognize(result, regionBottomPx, onResult)
                }

                override fun onFailure(errorCode: Int) {
                    logger.warn(TAG, "Captura de tela recusada (código $errorCode)")
                    onResult(emptyList())
                }
            },
        )
    }

    // Converte o buffer de hardware em bitmap comum, recorta a faixa do mapa e roda o OCR.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun recognize(
        result: AccessibilityService.ScreenshotResult,
        regionBottomPx: Int,
        onResult: OnceCallback,
    ) {
        try {
            val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            val full = hardware?.copy(Bitmap.Config.ARGB_8888, false)
            hardware?.recycle()
            result.hardwareBuffer.close()
            if (full == null) {
                onResult(emptyList())
                return
            }

            val height = regionBottomPx.coerceIn(1, full.height)
            val region = Bitmap.createBitmap(full, 0, 0, full.width, height)

            recognizer.process(InputImage.fromBitmap(region, 0))
                .addOnSuccessListener { text ->
                    val lines = text.textBlocks.flatMap { block -> block.lines.map { it.text } }
                    logger.debug(TAG, "OCR do mapa: $lines")
                    onResult(lines)
                }
                .addOnFailureListener { e ->
                    logger.warn(TAG, "OCR falhou", e)
                    onResult(emptyList())
                }
                .addOnCompleteListener {
                    if (region !== full) region.recycle()
                    full.recycle()
                }
        } catch (e: Exception) {
            logger.error(TAG, "Falha ao processar a captura", e)
            onResult(emptyList())
        }
    }

    /** Garante que o resultado seja entregue uma só vez, mesmo com caminhos de erro concorrentes. */
    private class OnceCallback(private val delegate: (List<String>) -> Unit) : (List<String>) -> Unit {
        private var delivered = false
        override fun invoke(lines: List<String>) {
            if (delivered) return
            delivered = true
            delegate(lines)
        }
    }

    private companion object {
        const val TAG = "ScreenOcrReader"
    }
}
