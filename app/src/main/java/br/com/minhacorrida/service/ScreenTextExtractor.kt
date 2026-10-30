package br.com.minhacorrida.service

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import br.com.minhacorrida.core.port.AppLogger

/**
 * Percorre a árvore de acessibilidade e devolve os textos visíveis na ordem de leitura.
 */
class ScreenTextExtractor(
    private val logger: AppLogger,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {

    /**
     * @param root nó raiz da janela do app de corrida.
     * @return textos e descrições de conteúdo não vazios.
     */
    fun extract(root: AccessibilityNodeInfo): List<String> {
        val texts = ArrayList<String>(64)
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        var visited = 0

        try {
            // DFS iterativa (evita estouro de pilha); filhos empilhados ao contrário
            // para que a ordem final seja de cima para baixo.
            while (stack.isNotEmpty() && visited < maxNodes) {
                val node = stack.removeLast()
                visited++

                val text = node.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) texts.add(text)

                val description = node.contentDescription?.toString()?.trim()
                if (!description.isNullOrEmpty() && description != text) texts.add(description)

                for (i in node.childCount - 1 downTo 0) {
                    node.getChild(i)?.let(stack::addLast)
                }
                if (node !== root) recycleCompat(node)
            }
        } catch (e: Exception) {
            logger.warn(TAG, "Leitura parcial da tela ($visited nós)", e)
        } finally {
            stack.forEach { if (it !== root) recycleCompat(it) }
        }
        return texts
    }

    @Suppress("DEPRECATION")
    private fun recycleCompat(node: AccessibilityNodeInfo) {
        // A partir do Android 13 a reciclagem é automática.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { node.recycle() }
        }
    }

    private companion object {
        const val TAG = "ScreenTextExtractor"
        const val DEFAULT_MAX_NODES = 600
    }
}
