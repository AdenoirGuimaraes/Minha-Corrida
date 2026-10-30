package br.com.minhacorrida.core

import br.com.minhacorrida.core.reading.ReadingPriorityPolicy
import br.com.minhacorrida.core.reading.ReadingTarget.Source
import br.com.minhacorrida.core.reading.WindowSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingPriorityPolicyTest {
    private val w = 1080
    private val h = 2400
    private val policy = ReadingPriorityPolicy()

    private val inDriveFront = WindowSnapshot("sinet.startup.inDriver", true, true, 0, 0, w, h)
    private val uberBubble = WindowSnapshot("com.ubercab.driver", false, false, 900, 1100, 1080, 1250)
    private val uberCardTop = WindowSnapshot("com.ubercab.driver", false, false, 0, 1200, w, 1700)
    private val uberCardBottom = WindowSnapshot("com.ubercab.driver", false, false, 0, 1700, w, 2350)
    private val gigu = WindowSnapshot("co.gigu.app", false, false, 0, 300, 200, 450)
    private val launcher = WindowSnapshot("com.sec.android.app.launcher", true, true, 0, 0, w, h)

    @Test
    fun `tela inicial do inDrive com a bolha da Uber le o inDrive`() {
        val t = policy.choose(listOf(uberBubble, gigu, inDriveFront), w, h)!!
        assertEquals(Source.FOREGROUND, t.source)
        assertEquals("sinet.startup.inDriver", t.packageName)
    }

    @Test
    fun `cartao da Uber tem preferencia sobre o inDrive em primeiro plano`() {
        val t = policy.choose(listOf(uberBubble, uberCardTop, gigu, inDriveFront), w, h)!!
        assertEquals(Source.UBER_CARD_OVERLAY, t.source)
        assertEquals("com.ubercab.driver", t.packageName)
    }

    @Test
    fun `OCR cobre as partes de cima e de baixo do cartao`() {
        val t = policy.choose(listOf(uberCardBottom, uberBubble, uberCardTop, inDriveFront), w, h)!!
        assertEquals(1200, t.ocrTopPx)
        assertEquals(2350, t.ocrBottomPx)
    }

    @Test
    fun `faixa baixa da parte de baixo do cartao tambem entra no OCR`() {
        val strip = WindowSnapshot("com.ubercab.driver", false, false, 0, 1700, w, 1900)
        val t = policy.choose(listOf(uberCardTop, strip, uberBubble, inDriveFront), w, h)!!
        assertEquals(1200, t.ocrTopPx)
        assertEquals(1900, t.ocrBottomPx)
    }

    @Test
    fun `faixa baixa sozinha nao e cartao`() {
        val strip = WindowSnapshot("com.ubercab.driver", false, false, 0, 2150, w, 2350)
        val t = policy.choose(listOf(strip, uberBubble, inDriveFront), w, h)!!
        assertEquals(Source.FOREGROUND, t.source)
    }

    @Test
    fun `sobreposicao do tamanho da tela le so a metade de baixo`() {
        val full = WindowSnapshot("com.ubercab.driver", false, false, 0, 0, w, h)
        val t = policy.choose(listOf(full, inDriveFront), w, h)!!
        assertEquals((h * 0.4).toInt(), t.ocrTopPx)
        assertEquals(h, t.ocrBottomPx)
    }

    @Test
    fun `cartao alto nao inclui o topo onde fica o leitor`() {
        val tall = WindowSnapshot("com.ubercab.driver", false, false, 0, 100, w, 1800)
        val t = policy.choose(listOf(tall, inDriveFront), w, h)!!
        assertEquals((h * 0.2).toInt(), t.ocrTopPx)
    }

    @Test
    fun `cartao ignorado devolve a leitura ao inDrive`() {
        val t = policy.choose(listOf(uberCardTop, inDriveFront), w, h, ignoreUberCard = true)!!
        assertEquals(Source.FOREGROUND, t.source)
    }

    @Test
    fun `cartao da Uber sobre outro app tambem e lido`() {
        val t = policy.choose(listOf(uberCardTop, launcher), w, h)!!
        assertEquals(Source.UBER_CARD_OVERLAY, t.source)
    }

    @Test
    fun `sem app de corrida na tela nao le nada`() {
        assertNull(policy.choose(listOf(uberBubble, gigu, launcher), w, h))
    }

    @Test
    fun `Uber em primeiro plano segue o modo normal`() {
        val uberFront = WindowSnapshot("com.ubercab.driver", true, true, 0, 0, w, h)
        val t = policy.choose(listOf(uberFront), w, h)!!
        assertEquals(Source.FOREGROUND, t.source)
        assertNull(t.ocrTopPx)
    }
}
