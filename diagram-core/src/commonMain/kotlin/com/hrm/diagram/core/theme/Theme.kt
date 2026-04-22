package com.hrm.diagram.core.theme

import com.hrm.diagram.core.draw.ArrowStyle
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ClusterStyle
import com.hrm.diagram.core.ir.EdgeStyle
import com.hrm.diagram.core.ir.NodeStyle

/**
 * Named color slots used by every diagram family. Parser-level overrides
 * (e.g. mermaid `style A fill:#f9f`, DOT `color=red`) override the matching slot
 * but never the whole palette.
 */
data class Palette(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val surface: Color,
    val onSurface: Color,
    val outline: Color,
    val muted: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
)

data class Typography(
    val bodyFont: FontSpec,
    val titleFont: FontSpec,
    val monoFont: FontSpec,
)

/**
 * Renderer-facing theme. Owns every cosmetic default; layout layer MUST NOT consult it.
 * Build via [DiagramTheme.Default] / [DiagramTheme.Dark] / your own copy.
 */
data class DiagramTheme(
    val palette: Palette,
    val typography: Typography,
    val nodeDefaults: NodeStyle,
    val edgeDefaults: EdgeStyle,
    val clusterDefaults: ClusterStyle,
    val arrowDefaults: ArrowStyle,
    val background: Color,
) {
    companion object {
        private val LightPalette = Palette(
            primary    = Color(0xFF1F6FEB.toInt()),
            secondary  = Color(0xFF8957E5.toInt()),
            accent     = Color(0xFFE3B341.toInt()),
            surface    = Color(0xFFFFFFFF.toInt()),
            onSurface  = Color(0xFF1F2328.toInt()),
            outline    = Color(0xFFD0D7DE.toInt()),
            muted      = Color(0xFF656D76.toInt()),
            danger     = Color(0xFFCF222E.toInt()),
            success    = Color(0xFF1A7F37.toInt()),
            warning    = Color(0xFF9A6700.toInt()),
        )
        private val DarkPalette = Palette(
            primary    = Color(0xFF58A6FF.toInt()),
            secondary  = Color(0xFFBC8CFF.toInt()),
            accent     = Color(0xFFE3B341.toInt()),
            surface    = Color(0xFF0D1117.toInt()),
            onSurface  = Color(0xFFC9D1D9.toInt()),
            outline    = Color(0xFF30363D.toInt()),
            muted      = Color(0xFF8B949E.toInt()),
            danger     = Color(0xFFF85149.toInt()),
            success    = Color(0xFF3FB950.toInt()),
            warning    = Color(0xFFD29922.toInt()),
        )
        private val DefaultTypography = Typography(
            bodyFont  = FontSpec(family = "sans-serif", sizeSp = 14f),
            titleFont = FontSpec(family = "sans-serif", sizeSp = 18f, weight = 600),
            monoFont  = FontSpec(family = "monospace", sizeSp = 13f),
        )

        private fun build(palette: Palette, background: Color): DiagramTheme = DiagramTheme(
            palette = palette,
            typography = DefaultTypography,
            nodeDefaults = NodeStyle(
                fill = ArgbColor(palette.surface.argb),
                stroke = ArgbColor(palette.outline.argb),
                strokeWidth = 1.5f,
                textColor = ArgbColor(palette.onSurface.argb),
            ),
            edgeDefaults = EdgeStyle(
                color = ArgbColor(palette.onSurface.argb),
                width = 1.5f,
            ),
            clusterDefaults = ClusterStyle(
                fill = null,
                stroke = ArgbColor(palette.outline.argb),
                strokeWidth = 1f,
            ),
            arrowDefaults = ArrowStyle(
                color = palette.onSurface,
                stroke = Stroke(width = 1.5f),
            ),
            background = background,
        )

        val Default: DiagramTheme = build(LightPalette, LightPalette.surface)
        val Dark: DiagramTheme = build(DarkPalette, DarkPalette.surface)
    }
}
