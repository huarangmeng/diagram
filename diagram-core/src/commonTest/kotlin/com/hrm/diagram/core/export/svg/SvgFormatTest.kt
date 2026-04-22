package com.hrm.diagram.core.export.svg

import kotlin.test.Test
import kotlin.test.assertEquals

class SvgFormatTest {
    @Test
    fun fmtIntegers() {
        assertEquals("0", fmt(0f))
        assertEquals("1", fmt(1f))
        assertEquals("-3", fmt(-3f))
    }

    @Test
    fun fmtDecimals() {
        assertEquals("1.5", fmt(1.5f))
        assertEquals("0.001", fmt(0.001f))
        assertEquals("-2.25", fmt(-2.25f))
    }

    @Test
    fun fmtRoundsToThreeDecimals() {
        // 1.23456 → 1.235 (rounded)
        assertEquals("1.235", fmt(1.23456f))
    }

    @Test
    fun escapeXmlReservedChars() {
        assertEquals("&lt;a&gt;&amp;&quot;&apos;", escapeXml("<a>&\"'"))
        assertEquals("plain", escapeXml("plain"))
    }
}
