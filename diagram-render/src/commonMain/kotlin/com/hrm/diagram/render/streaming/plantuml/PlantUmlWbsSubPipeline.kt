package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.TreeNode
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.tree.MindmapLayout
import com.hrm.diagram.layout.tree.TreeLayoutKind
import com.hrm.diagram.parser.plantuml.PlantUmlWbsParser
import com.hrm.diagram.render.streaming.DiagramSnapshot

internal class PlantUmlWbsSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private companion object {
        val palette = PlantUmlTreeNodePalette(
            defaultNodeFill = Color(0xFFFFF8E1.toInt()),
            defaultNodeStroke = Color(0xFFF9A825.toInt()),
            rootFill = Color(0xFFFFECB3.toInt()),
            rootStroke = Color(0xFFF57F17.toInt()),
            textColor = Color(0xFF263238.toInt()),
            edgeColor = Color(0xFF90A4AE.toInt()),
        )
        val chrome = PlantUmlTreeNodeChrome(
            rootCornerRadius = 10f,
            childCornerRadius = 4f,
            rootStrokeWidth = 2f,
            childStrokeWidth = 1.5f,
        )
    }

    private val parser = PlantUmlWbsParser()
    private val layout = MindmapLayout(textMeasurer, TreeLayoutKind.Wbs)
    private val font = FontSpec(family = "sans-serif", sizeSp = 12f)
    private val stereotypeGap = 2f

    override fun acceptLine(line: String) = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean) = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = parser.snapshot()
        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        ).copy(seq = seq)
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laid,
            drawCommands = render(ir, laid),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    private fun render(ir: TreeIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val boxless = parseBoxless(ir)
        val inlineColors = PlantUmlTreeRenderSupport.parseNodeColorMap(ir.styleHints.extras[PlantUmlWbsParser.INLINE_COLOR_KEY].orEmpty())
        val styleColors = PlantUmlTreeRenderSupport.parseNodeColorMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_COLOR_KEY].orEmpty())
        val styleLineColors = PlantUmlTreeRenderSupport.parseNodeColorMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_LINE_COLOR_KEY].orEmpty())
        val styleFontColors = PlantUmlTreeRenderSupport.parseNodeColorMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_FONT_COLOR_KEY].orEmpty())
        val styleFontNames = PlantUmlTreeRenderSupport.parseNodeStringMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_FONT_NAME_KEY].orEmpty())
        val styleFontSizes = PlantUmlTreeRenderSupport.parseNodeStringMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_FONT_SIZE_KEY].orEmpty())
        val styleFontStyles = PlantUmlTreeRenderSupport.parseNodeStringMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_FONT_STYLE_KEY].orEmpty())
        val styleLineThickness = PlantUmlTreeRenderSupport.parseNodeFloatMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_LINE_THICKNESS_KEY].orEmpty())
        val styleRoundCorners = PlantUmlTreeRenderSupport.parseNodeFloatMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_ROUND_CORNER_KEY].orEmpty())
        val styleShadowing = PlantUmlTreeRenderSupport.parseNodeBooleanMap(ir.styleHints.extras[PlantUmlWbsParser.STYLE_SHADOWING_KEY].orEmpty())
        val stereotypes = PlantUmlTreeRenderSupport.parseNodeStringMap(ir.styleHints.extras[PlantUmlWbsParser.STEREOTYPE_KEY].orEmpty())
        val leadingVisuals = PlantUmlTreeRenderSupport.parseNodeLeadingVisualMap(ir.styleHints.extras[PlantUmlWbsParser.LEADING_VISUAL_KEY].orEmpty())

        fun drawEdges(parent: TreeNode) {
            val pr = laid.nodePositions[parent.id] ?: return
            for (c in parent.children) {
                val cr = laid.nodePositions[c.id] ?: continue
                PlantUmlTreeRenderSupport.appendCubicConnector(
                    out = out,
                    parentRect = pr,
                    childRect = cr,
                    color = styleLineColors[c.id] ?: palette.edgeColor,
                    strokeWidth = styleLineThickness[c.id] ?: chrome.childStrokeWidth,
                )
                drawEdges(c)
            }
        }

        fun drawNode(n: TreeNode, isRoot: Boolean) {
            val r = laid.nodePositions[n.id] ?: return
            val nodeBoxless = n.id in boxless
            val inlineColor = inlineColors[n.id]
            val styleColor = styleColors[n.id]
            val styleLineColor = styleLineColors[n.id]
            val styleFontColor = styleFontColors[n.id]
            val styleFontName = styleFontNames[n.id]
            val styleFontSize = styleFontSizes[n.id]
            val styleFontStyle = styleFontStyles[n.id]
            val styleLineWidth = styleLineThickness[n.id]
            val styleRoundCorner = styleRoundCorners[n.id]
            val styleShadow = styleShadowing[n.id] == true
            val stereotype = stereotypes[n.id]
            val leadingVisual = leadingVisuals[n.id]
            val effectiveFill = inlineColor ?: styleColor ?: if (isRoot) palette.rootFill else palette.defaultNodeFill
            val effectiveStroke = styleLineColor
                ?: (inlineColor ?: styleColor)?.let { PlantUmlTreeRenderSupport.darken(it, 0.18f) }
                ?: if (isRoot) palette.rootStroke else palette.defaultNodeStroke
            val effectiveText = if (nodeBoxless) {
                styleFontColor ?: (inlineColor ?: styleColor)?.let { PlantUmlTreeRenderSupport.darken(it, 0.45f) } ?: palette.textColor
            } else {
                styleFontColor ?: if (PlantUmlTreeRenderSupport.isDark(effectiveFill)) Color(0xFFFFFFFF.toInt()) else palette.textColor
            }
            PlantUmlTreeRenderSupport.appendNodeChrome(
                out = out,
                rect = r,
                isRoot = isRoot,
                boxless = nodeBoxless,
                fill = effectiveFill,
                strokeColor = effectiveStroke,
                chrome = chrome,
                cornerRadiusOverride = styleRoundCorner,
                strokeWidthOverride = styleLineWidth,
                shadow = styleShadow,
            )
            val label = (n.label as? RichLabel.Plain)?.text ?: ""
            val baseFont = if (isRoot) font.copy(weight = 600) else font
            val bodyFont = PlantUmlTreeRenderSupport.resolveFontSpec(baseFont, styleFontName, styleFontSize, styleFontStyle)
            PlantUmlTreeRenderSupport.appendCenteredNodeText(
                out = out,
                textMeasurer = textMeasurer,
                rect = r,
                label = label,
                stereotype = stereotype,
                leadingVisual = leadingVisual,
                bodyFont = bodyFont,
                color = effectiveText,
                stereotypeGap = stereotypeGap,
            )
            n.children.forEach { drawNode(it, false) }
        }

        drawEdges(ir.root)
        drawNode(ir.root, true)
        return out
    }

    private fun parseBoxless(ir: TreeIR): Set<NodeId> =
        ir.styleHints.extras[PlantUmlWbsParser.BOXLESS_KEY]
            .orEmpty()
            .split("||")
            .filter { it.isNotEmpty() }
            .map { NodeId(it) }
            .toSet()
}
