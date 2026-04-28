package com.hrm.diagram.parser.mermaid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MermaidFrontmatterThemeConfigTest {
    @Test
    fun parses_chart_config_sections() {
        val r = MermaidStyleParsers.parseFrontmatterThemeConfig(
            """
            ---
            config:
              kanban:
                ticketBaseUrl: 'https://example.test/browse/#TICKET#'
              timeline:
                disableMulticolor: true
              xyChart:
                showDataLabel: true
            ---
            """.trimIndent(),
        )
        assertNotNull(r)
        assertEquals("https://example.test/browse/#TICKET#", r.config.chartConfig["kanban.ticketBaseUrl"], "chartConfig=${r.config.chartConfig}")
        assertEquals("true", r.config.chartConfig["timeline.disableMulticolor"], "chartConfig=${r.config.chartConfig}")
        assertEquals("true", r.config.chartConfig["xyChart.showDataLabel"], "chartConfig=${r.config.chartConfig}")
    }

    @Test
    fun parses_nested_theme_variables_sections() {
        val r = MermaidStyleParsers.parseFrontmatterThemeConfig(
            """
            ---
            config:
              themeVariables:
                xyChart:
                  titleColor: '#ff0000'
                  plotColorPalette: '#0000ff'
            ---
            """.trimIndent(),
        )
        assertNotNull(r)
        assertEquals("#ff0000", r.config.themeTokens?.raw?.get("titleColor"), "raw=${r.config.themeTokens?.raw}")
        assertEquals("#0000ff", r.config.themeTokens?.raw?.get("plotColorPalette"), "raw=${r.config.themeTokens?.raw}")
    }
}
