package com.hrm.diagram.core.theme

import com.hrm.diagram.core.draw.ArrowStyle
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ClusterStyle
import com.hrm.diagram.core.ir.EdgeStyle
import com.hrm.diagram.core.ir.NodeStyle

data class ThemeColors(
    val canvas: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val accent: Color,
    val accentSecondary: Color,
    val accentTertiary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val selection: Color,
    val diagnostic: Color,
)

data class Typography(
    val bodyFont: FontSpec,
    val titleFont: FontSpec,
    val monoFont: FontSpec,
)

data class GraphColors(
    val background: Color? = null,
    val nodeFill: Color? = null,
    val nodeStroke: Color? = null,
    val nodeText: Color? = null,
    val edge: Color? = null,
    val edgeLabelText: Color? = null,
    val edgeLabelBackground: Color? = null,
    val clusterFill: Color? = null,
    val clusterStroke: Color? = null,
    val noteFill: Color? = null,
    val noteStroke: Color? = null,
    val noteText: Color? = null,
)

data class PieColors(
    val titleText: Color? = null,
    val legendText: Color? = null,
    val border: Color? = null,
    val slices: List<Color> = emptyList(),
)

data class TreeColors(
    val nodeFill: Color? = null,
    val nodeStroke: Color? = null,
    val nodeText: Color? = null,
    val rootFill: Color? = null,
    val rootStroke: Color? = null,
    val rootText: Color? = null,
    val edge: Color? = null,
)

data class SequenceColors(
    val headerFill: Color? = null,
    val headerStroke: Color? = null,
    val headerText: Color? = null,
    val lifeline: Color? = null,
    val message: Color? = null,
    val messageText: Color? = null,
    val noteFill: Color? = null,
    val noteStroke: Color? = null,
    val noteText: Color? = null,
    val activationFill: Color? = null,
    val activationStroke: Color? = null,
    val fragmentStroke: Color? = null,
)

data class TimeSeriesColors(
    val titleText: Color? = null,
    val labelText: Color? = null,
    val axis: Color? = null,
    val border: Color? = null,
    val alternateRowBackground: Color? = null,
    val normalFill: Color? = null,
    val activeFill: Color? = null,
    val doneFill: Color? = null,
    val criticalFill: Color? = null,
    val milestoneFill: Color? = null,
    val slotFill: Color? = null,
    val slotStroke: Color? = null,
    val itemFill: Color? = null,
    val itemStroke: Color? = null,
)

/**
 * Renderer-facing theme. Owns every cosmetic default; layout layer MUST NOT consult it.
 * Build via [DiagramTheme.Default] / [DiagramTheme.Dark] / your own copy.
 */
data class DiagramTheme(
    val colors: ThemeColors,
    val typography: Typography,
    val nodeDefaults: NodeStyle,
    val edgeDefaults: EdgeStyle,
    val clusterDefaults: ClusterStyle,
    val arrowDefaults: ArrowStyle,
    val background: Color,
    val graphColors: GraphColors = GraphColors(),
    val pieColors: PieColors = PieColors(),
    val treeColors: TreeColors = TreeColors(),
    val sequenceColors: SequenceColors = SequenceColors(),
    val timeSeriesColors: TimeSeriesColors = TimeSeriesColors(),
) {
    companion object {
        private val LightColors = ThemeColors(
            canvas = Color(0xFFFFFFFF.toInt()),
            surface = Color(0xFFFFFFFF.toInt()),
            surfaceAlt = Color(0xFFF6F8FA.toInt()),
            textPrimary = Color(0xFF1F2328.toInt()),
            textSecondary = Color(0xFF656D76.toInt()),
            border = Color(0xFFD0D7DE.toInt()),
            accent = Color(0xFF1F6FEB.toInt()),
            accentSecondary = Color(0xFF8957E5.toInt()),
            accentTertiary = Color(0xFFE3B341.toInt()),
            success = Color(0xFF1A7F37.toInt()),
            warning = Color(0xFF9A6700.toInt()),
            danger = Color(0xFFCF222E.toInt()),
            selection = Color(0xFFB6D1FF.toInt()),
            diagnostic = Color(0xFFCF222E.toInt()),
        )
        private val DarkColors = ThemeColors(
            canvas = Color(0xFF0D1117.toInt()),
            surface = Color(0xFF0D1117.toInt()),
            surfaceAlt = Color(0xFF161B22.toInt()),
            textPrimary = Color(0xFFC9D1D9.toInt()),
            textSecondary = Color(0xFF8B949E.toInt()),
            border = Color(0xFF30363D.toInt()),
            accent = Color(0xFF58A6FF.toInt()),
            accentSecondary = Color(0xFFBC8CFF.toInt()),
            accentTertiary = Color(0xFFE3B341.toInt()),
            success = Color(0xFF3FB950.toInt()),
            warning = Color(0xFFD29922.toInt()),
            danger = Color(0xFFF85149.toInt()),
            selection = Color(0xFF1F6FEB.toInt()),
            diagnostic = Color(0xFFF85149.toInt()),
        )
        private val DefaultTypography = Typography(
            bodyFont = FontSpec(family = "sans-serif", sizeSp = 14f),
            titleFont = FontSpec(family = "sans-serif", sizeSp = 18f, weight = 600),
            monoFont = FontSpec(family = "monospace", sizeSp = 13f),
        )

        private fun build(colors: ThemeColors): DiagramTheme = DiagramTheme(
            colors = colors,
            typography = DefaultTypography,
            nodeDefaults = NodeStyle(
                fill = ArgbColor(colors.surface.argb),
                stroke = ArgbColor(colors.border.argb),
                strokeWidth = 1.5f,
                textColor = ArgbColor(colors.textPrimary.argb),
            ),
            edgeDefaults = EdgeStyle(
                color = ArgbColor(colors.textPrimary.argb),
                width = 1.5f,
            ),
            clusterDefaults = ClusterStyle(
                fill = null,
                stroke = ArgbColor(colors.border.argb),
                strokeWidth = 1f,
            ),
            arrowDefaults = ArrowStyle(
                color = colors.textPrimary,
                stroke = Stroke(width = 1.5f),
            ),
            background = colors.canvas,
            graphColors = GraphColors(
                background = colors.canvas,
                nodeFill = colors.surface,
                nodeStroke = colors.border,
                nodeText = colors.textPrimary,
                edge = colors.textPrimary,
                edgeLabelText = colors.textPrimary,
                edgeLabelBackground = colors.surface.copy(alpha = 0.94f),
                clusterFill = colors.surfaceAlt,
                clusterStroke = colors.border,
                noteFill = colors.warning.copy(alpha = 0.12f),
                noteStroke = colors.warning,
                noteText = colors.textPrimary,
            ),
            pieColors = PieColors(
                titleText = colors.textPrimary,
                legendText = colors.textPrimary,
                border = colors.border,
                slices = listOf(
                    colors.accent,
                    colors.success,
                    colors.warning,
                    colors.danger,
                    colors.accentSecondary,
                    colors.accentTertiary,
                ),
            ),
            treeColors = TreeColors(
                nodeFill = colors.surface,
                nodeStroke = colors.border,
                nodeText = colors.textPrimary,
                rootFill = colors.accent.copy(alpha = 0.12f),
                rootStroke = colors.accent,
                rootText = colors.textPrimary,
                edge = colors.border,
            ),
            sequenceColors = SequenceColors(
                headerFill = colors.accent.copy(alpha = 0.12f),
                headerStroke = colors.accent,
                headerText = colors.textPrimary,
                lifeline = colors.textSecondary,
                message = colors.textPrimary,
                messageText = colors.textPrimary,
                noteFill = colors.warning.copy(alpha = 0.12f),
                noteStroke = colors.warning,
                noteText = colors.textPrimary,
                activationFill = colors.surface,
                activationStroke = colors.accent,
                fragmentStroke = colors.accentSecondary,
            ),
            timeSeriesColors = TimeSeriesColors(
                titleText = colors.textPrimary,
                labelText = colors.textPrimary,
                axis = colors.border,
                border = colors.border,
                alternateRowBackground = colors.textPrimary.copy(alpha = 0.03f),
                normalFill = colors.textSecondary.copy(alpha = 0.45f),
                activeFill = colors.accent.copy(alpha = 0.8f),
                doneFill = colors.success.copy(alpha = 0.8f),
                criticalFill = colors.danger.copy(alpha = 0.82f),
                milestoneFill = colors.warning.copy(alpha = 0.82f),
                slotFill = colors.accent.copy(alpha = 0.05f),
                slotStroke = colors.border,
                itemFill = colors.accent.copy(alpha = 0.14f),
                itemStroke = colors.accent,
            ),
        )

        val Default: DiagramTheme = build(LightColors)
        val Dark: DiagramTheme = build(DarkColors)
    }
}

private fun Color.copy(alpha: Float): Color {
    val clamped = alpha.coerceIn(0f, 1f)
    val a = (clamped * 255f).toInt().coerceIn(0, 255)
    return Color((argb and 0x00FFFFFF) or (a shl 24))
}
