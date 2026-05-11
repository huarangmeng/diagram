package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.TreeNode
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.tree.MindmapLayout
import com.hrm.diagram.parser.plantuml.PlantUmlMindmapParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.min

internal class PlantUmlMindmapSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private companion object {
        val palette = PlantUmlTreeNodePalette(
            defaultNodeFill = Color(0xFFE8F5E9.toInt()),
            defaultNodeStroke = Color(0xFF2E7D32.toInt()),
            rootFill = Color(0xFFE3F2FD.toInt()),
            rootStroke = Color(0xFF1565C0.toInt()),
            textColor = Color(0xFF263238.toInt()),
            edgeColor = Color(0xFF90A4AE.toInt()),
        )
        val chrome = PlantUmlTreeNodeChrome(
            rootCornerRadius = 14f,
            childCornerRadius = 6f,
            rootStrokeWidth = 2f,
            childStrokeWidth = 1.5f,
        )
    }

    private val parser = PlantUmlMindmapParser()
    private val layout = MindmapLayout(textMeasurer)
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
        val inlineColors = PlantUmlTreeRenderSupport.parseNodeColorMap(ir.styleHints.extras[PlantUmlMindmapParser.INLINE_COLOR_KEY].orEmpty())
        val styleColors = PlantUmlTreeRenderSupport.parseNodeColorMap(ir.styleHints.extras[PlantUmlMindmapParser.STYLE_COLOR_KEY].orEmpty())
        val styleLineColors = PlantUmlTreeRenderSupport.parseNodeColorMap(ir.styleHints.extras[PlantUmlMindmapParser.STYLE_LINE_COLOR_KEY].orEmpty())
        val styleFontColors = PlantUmlTreeRenderSupport.parseNodeColorMap(ir.styleHints.extras[PlantUmlMindmapParser.STYLE_FONT_COLOR_KEY].orEmpty())
        val styleRoundCorners = PlantUmlTreeRenderSupport.parseNodeFloatMap(ir.styleHints.extras[PlantUmlMindmapParser.STYLE_ROUND_CORNER_KEY].orEmpty())
        val stereotypes = PlantUmlTreeRenderSupport.parseNodeStringMap(ir.styleHints.extras[PlantUmlMindmapParser.STEREOTYPE_KEY].orEmpty())
        val leadingVisuals = PlantUmlTreeRenderSupport.parseNodeLeadingVisualMap(ir.styleHints.extras[PlantUmlMindmapParser.LEADING_VISUAL_KEY].orEmpty())

        fun drawEdges(parent: TreeNode) {
            val pr = laid.nodePositions[parent.id] ?: return
            for (c in parent.children) {
                val cr = laid.nodePositions[c.id] ?: continue
                PlantUmlTreeRenderSupport.appendCubicConnector(
                    out = out,
                    parentRect = pr,
                    childRect = cr,
                    color = styleLineColors[c.id] ?: palette.edgeColor,
                )
                drawEdges(c)
            }
        }

        fun drawNode(n: TreeNode, isRoot: Boolean) {
            val r = laid.nodePositions[n.id] ?: return
            val inlineColor = inlineColors[n.id]
            val styleColor = styleColors[n.id]
            val styleLineColor = styleLineColors[n.id]
            val styleFontColor = styleFontColors[n.id]
            val styleRoundCorner = styleRoundCorners[n.id]
            val fill = inlineColor ?: styleColor ?: if (isRoot) palette.rootFill else palette.defaultNodeFill
            val strokeColor = styleLineColor
                ?: (inlineColor ?: styleColor)?.let { PlantUmlTreeRenderSupport.darken(it, 0.18f) }
                ?: if (isRoot) palette.rootStroke else palette.defaultNodeStroke
            PlantUmlTreeRenderSupport.appendNodeChrome(
                out = out,
                rect = r,
                isRoot = isRoot,
                boxless = false,
                fill = fill,
                strokeColor = strokeColor,
                chrome = chrome,
                cornerRadiusOverride = styleRoundCorner,
            )

            val label = (n.label as? RichLabel.Plain)?.text ?: ""
            val stereotype = stereotypes[n.id]
            val leadingVisual = leadingVisuals[n.id]
            val bodyFont = if (isRoot) font.copy(weight = 600) else font
            val effectiveTextColor = styleFontColor
                ?: if (PlantUmlTreeRenderSupport.isDark(fill)) Color(0xFFFFFFFF.toInt()) else palette.textColor
            PlantUmlTreeRenderSupport.appendCenteredNodeText(
                out = out,
                textMeasurer = textMeasurer,
                rect = r,
                label = label,
                stereotype = stereotype,
                leadingVisual = leadingVisual,
                bodyFont = bodyFont,
                color = effectiveTextColor,
                stereotypeGap = stereotypeGap,
            )
            n.children.forEach { drawNode(it, false) }
        }

        drawEdges(ir.root)
        drawNode(ir.root, true)
        return out
    }
}
