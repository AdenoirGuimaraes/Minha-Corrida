package br.com.minhacorrida.core

import br.com.minhacorrida.core.parser.NumberParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberParserTest {
    @Test fun `formato brasileiro`() = assertEquals("8.91", NumberParser.parse("8,91").toString())
    @Test fun `formato americano`() = assertEquals("6.6", NumberParser.parse("6.6").toString())
    @Test fun `milhar brasileiro`() = assertEquals("1234.56", NumberParser.parse("1.234,56").toString())
    @Test fun `milhar americano`() = assertEquals("1234.56", NumberParser.parse("1,234.56").toString())
    @Test fun `inteiro`() = assertEquals("10", NumberParser.parse("10").toString())
    @Test fun `texto invalido`() = assertNull(NumberParser.parse("abc"))
}
