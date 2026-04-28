package com.hrm.diagram.parser.mermaid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidStyleParsersTest {

    @Test
    fun parse_classDef_basic_colors_and_width() {
        val r = MermaidStyleParsers.parseClassDefLine("classDef warn fill:#f96,stroke:#333,stroke-width:4px;")
        assertNotNull(r)
        assertEquals(1, r.classes.size)
        assertEquals("warn", r.classes[0].name)
        assertNotNull(r.classes[0].decl.fill)
        assertNotNull(r.classes[0].decl.stroke)
        assertEquals(4f, r.classes[0].decl.strokeWidthPx)
        assertTrue(r.diagnostics.isEmpty())
    }

    @Test
    fun parse_styleDecl_short_hex_colors_expand_correctly() {
        val (decl, diags) = MermaidStyleParsers.parseStyleDecl("fill:#f9f,stroke:#333,color:#bbf")
        assertEquals(0xFFFF99FF.toInt(), decl.fill?.argb)
        assertEquals(0xFF333333.toInt(), decl.stroke?.argb)
        assertEquals(0xFFBBBBFF.toInt(), decl.textColor?.argb)
        assertTrue(diags.isEmpty())
    }

    @Test
    fun parse_styleDecl_non_hex_color_emits_warning() {
        val (decl, diags) = MermaidStyleParsers.parseStyleDecl("fill:red,stroke:#333")
        assertEquals(null, decl.fill)
        assertNotNull(decl.stroke)
        assertTrue(diags.any { it.code == "MERMAID-W011" })
    }

    @Test
    fun parse_frontmatter_themeVariables_subset() {
        val fm = """
            ---
            config:
              theme: 'base'
              themeVariables:
                primaryColor: '#BB2528'
                fontSize: 16px
            ---
        """.trimIndent()
        val r = MermaidStyleParsers.parseFrontmatterThemeConfig(fm)
        assertNotNull(r)
        assertEquals(MermaidThemeName.Base, r.config.theme)
        val tokens = r.config.themeTokens
        assertNotNull(tokens)
        assertNotNull(tokens.primaryColor)
        assertEquals(16f, tokens.fontSizePx)
    }
}
