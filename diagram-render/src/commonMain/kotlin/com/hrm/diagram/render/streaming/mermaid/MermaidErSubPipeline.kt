package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.core.text.TextMetrics
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.mermaid.MermaidErParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import kotlin.math.sqrt

/** Sub-pipeline for Mermaid `erDiagram` sources (Phase 1 subset). */
internal class MermaidErSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {

    private val parser = MermaidErParser()
    private val entityFont = FontSpec(family = "sans-serif", sizeSp = 13f, weight = 600)
    private val attributeFont = FontSpec(family = "sans-serif", sizeSp = 12f)
    private val flagFont = FontSpec(family = "sans-serif", sizeSp = 10f, weight = 600)
    private val relationFont = FontSpec(family = "sans-serif", sizeSp = 11f)
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val nodeMetrics: MutableMap<NodeId, TextMetrics> = HashMap()
    private var graphStyles: MermaidGraphStyleState? = null
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(140f, 56f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(140f, 56f) },
    )

    override fun updateGraphStyles(styles: MermaidGraphStyleState) {
        graphStyles = styles
    }

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val newPatches = ArrayList<IrPatch>()
        val addedNodeIds = ArrayList<NodeId>()
        for (lineToks in lines) {
            val batch: IrPatchBatch = parser.acceptLine(lineToks)
            for (p in batch.patches) {
                newPatches += p
                if (p is IrPatch.AddNode) addedNodeIds += p.node.id
            }
        }

        val ir0: GraphIR = parser.snapshot()
        val ir: GraphIR = graphStyles?.applyTo(ir0) ?: ir0
        val needRemeasure = isFinal
        for (n in ir.nodes) {
            if (!needRemeasure && n.id in nodeSizes) continue
            val (size, metrics) = measureNode(n)
            nodeSizes[n.id] = size
            nodeMetrics[n.id] = metrics
        }
        val opts = LayoutOptions(
            direction = ir.styleHints.direction,
            incremental = !isFinal,
            allowGlobalReflow = isFinal,
        )
        val laidOut: LaidOutDiagram = layout.layout(previousSnapshot.laidOut, ir, opts).copy(seq = seq)
        val drawCommands = renderDraw(ir, laidOut)
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }

        val snapshot = DiagramSnapshot(
            ir = ir,
            laidOut = laidOut,
            drawCommands = drawCommands,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        val patch = SessionPatch(
            seq = seq,
            addedNodes = addedNodeIds,
            addedEdges = newPatches.filterIsInstance<IrPatch.AddEdge>().map { it.edge },
            addedDrawCommands = drawCommands,
            newDiagnostics = newDiagnostics,
            isFinal = isFinal,
        )
        return PipelineAdvance(
            snapshot = snapshot,
            patch = patch,
            irBatch = IrPatchBatch(seq, newPatches),
        )
    }

    override fun dispose() {
        nodeSizes.clear()
        nodeMetrics.clear()
    }

    private fun labelTextOf(n: Node): String =
        (n.label as? RichLabel.Plain)?.text?.takeIf { it.isNotEmpty() } ?: n.id.value

    private fun measureNode(n: Node): Pair<Size, TextMetrics> {
        val text = labelTextOf(n)
        val maxWrap = 260f
        val raw = textMeasurer.measure(text, fontForNode(n), maxWidth = maxWrap)
        val (padX, padY) = when {
            isAttributeNode(n) -> 22f to 14f
            n.shape is NodeShape.RoundedBox -> 18f to 12f
            else -> 18f to 14f
        }
        val minW = if (isAttributeNode(n)) 120f else 104f
        val minH = if (isAttributeNode(n)) 44f else 48f
        val w = (raw.width + 2 * padX).coerceAtLeast(minW)
        val h = (raw.height + 2 * padY).coerceAtLeast(minH)
        return Size(w, h) to raw
    }

    private fun renderDraw(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>(ir.nodes.size * 3 + ir.edges.size * 2)
        val fallbackEntityFill = Color(0xFFE8F5E9U.toInt())
        val fallbackEntityStroke = Color(0xFF2E7D32U.toInt())
        val fallbackEntityText = Color(0xFF1B5E20U.toInt())
        val relationLabelText = Color(0xFF263238U.toInt())
        val fallbackRelationLabelBg = Color(0xFFF5F5F5U.toInt())
        val attributeLinkStroke = Stroke(width = 1f, dash = listOf(5f, 5f))
        val relationStroke = Stroke(width = 1.5f)
        val relationBadgePadX = 6f
        val relationBadgePadY = 4f

        for (n in ir.nodes) {
            val r = laidOut.nodePositions[n.id] ?: continue
            val fill = colorOf(n.style.fill, fallbackEntityFill)
            val strokeColor = colorOf(n.style.stroke, fallbackEntityStroke)
            val textColor = colorOf(n.style.textColor, fallbackEntityText)
            val strokeWidth = n.style.strokeWidth ?: if (isAttributeNode(n)) 1.25f else 1.5f
            val stroke = Stroke(width = strokeWidth)
            val corner = when {
                isAttributeNode(n) -> minOf(r.size.height / 2f, 16f)
                n.shape is NodeShape.RoundedBox -> 12f
                else -> 4f
            }
            out += DrawCommand.FillRect(rect = r, color = fill, corner = corner, z = 1)
            out += DrawCommand.StrokeRect(rect = r, stroke = stroke, color = strokeColor, corner = corner, z = 2)
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            if (isAttributeNode(n)) {
                drawAttributeNode(out, n, r, fill, strokeColor, textColor)
            } else {
                out += DrawCommand.DrawText(
                    text = labelTextOf(n),
                    origin = Point(cx, cy),
                    font = entityFont,
                    color = textColor,
                    maxWidth = r.size.width - 16f,
                    anchorX = TextAnchorX.Center,
                    anchorY = TextAnchorY.Middle,
                    z = 3,
                )
            }
        }

        for ((idx, route) in laidOut.edgeRoutes.withIndex()) {
            val pts = route.points
            if (pts.size < 2) continue
            val ops = ArrayList<PathOp>(pts.size)
            ops += PathOp.MoveTo(pts[0])
            when (route.kind) {
                RouteKind.Bezier -> {
                    var i = 1
                    while (i + 2 < pts.size) {
                        ops += PathOp.CubicTo(pts[i], pts[i + 1], pts[i + 2])
                        i += 3
                    }
                    if (i < pts.size) ops += PathOp.LineTo(pts.last())
                }
                else -> for (k in 1 until pts.size) ops += PathOp.LineTo(pts[k])
            }
            val path = PathCmd(ops)
            val edge = ir.edges.getOrNull(idx) ?: continue
            val isAttributeLink = edge.label == null
            val edgeColor = colorOf(edge.style.color, Color(0xFF455A64U.toInt()))
            val edgeStroke = if (isAttributeLink) {
                attributeLinkStroke
            } else {
                Stroke(width = edge.style.width ?: relationStroke.width, dash = edge.style.dash)
            }
            out += DrawCommand.StrokePath(path = path, stroke = edgeStroke, color = edgeColor, z = 0)
            if (edge.arrow != ArrowEnds.None) {
                val endTail = pts[pts.size - 2]
                val endHead = pts.last()
                val startTail = pts[1]
                val startHead = pts[0]
                when (edge.arrow) {
                    ArrowEnds.None -> Unit
                    ArrowEnds.ToOnly -> out += arrowHead(endTail, endHead, edgeColor)
                    ArrowEnds.FromOnly -> out += arrowHead(startTail, startHead, edgeColor)
                    ArrowEnds.Both -> {
                        out += arrowHead(endTail, endHead, edgeColor)
                        out += arrowHead(startTail, startHead, edgeColor)
                    }
                }
            }

            if (isAttributeLink) continue
            val labelBg = edge.style.labelBg?.let { Color(it.argb) } ?: fallbackRelationLabelBg
            drawRelationshipBadge(
                out = out,
                edge = edge,
                midPoint = pts[pts.size / 2],
                bgColor = labelBg,
                textColor = relationLabelText,
                borderColor = edgeColor,
                padX = relationBadgePadX,
                padY = relationBadgePadY,
            )
        }

        return out
    }

    private fun drawAttributeNode(
        out: MutableList<DrawCommand>,
        node: Node,
        rect: Rect,
        fill: Color,
        strokeColor: Color,
        textColor: Color,
    ) {
        val flags = attributeFlagsOf(node)
        if (flags.isNotEmpty()) {
            val badgeText = flags.joinToString(" / ")
            val badgeMetrics = textMeasurer.measure(badgeText, flagFont)
            val badgeRect = Rect.ltrb(
                rect.left + 8f,
                rect.top + 6f,
                rect.left + 8f + badgeMetrics.width + 10f,
                rect.top + 6f + badgeMetrics.height + 6f,
            )
            out += DrawCommand.FillRect(rect = badgeRect, color = strokeColor, corner = 8f, z = 3)
            out += DrawCommand.DrawText(
                text = badgeText,
                origin = Point((badgeRect.left + badgeRect.right) / 2f, (badgeRect.top + badgeRect.bottom) / 2f),
                font = flagFont,
                color = fill,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Middle,
                z = 4,
            )
        }

        val label = labelTextOf(node)
        val type = node.payload[MermaidErParser.ER_ATTRIBUTE_TYPE_KEY]
        val name = label.substringBefore(':').trim()
        val valueText = buildString {
            append(name)
            if (!type.isNullOrBlank()) {
                append("\n")
                append(type)
            }
        }
        out += DrawCommand.DrawText(
            text = valueText,
            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f + if (flags.isNotEmpty()) 6f else 0f),
            font = attributeFont,
            color = textColor,
            maxWidth = rect.size.width - 16f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Middle,
            z = 4,
        )
    }

    private fun drawRelationshipBadge(
        out: MutableList<DrawCommand>,
        edge: Edge,
        midPoint: Point,
        bgColor: Color,
        textColor: Color,
        borderColor: Color,
        padX: Float,
        padY: Float,
    ) {
        val raw = (edge.label as? RichLabel.Plain)?.text ?: return
        if (raw.isBlank()) return
        val cardinality = raw.substringBefore(' ').trim()
        val relation = raw.substringAfter(' ', "").trim()

        val cardMetrics = textMeasurer.measure(cardinality, flagFont)
        val relationMetrics = relation.takeIf { it.isNotEmpty() }?.let { textMeasurer.measure(it, relationFont) }
        val width = maxOf(cardMetrics.width + 2 * padX, (relationMetrics?.width ?: 0f) + 2 * padX)
        val height = cardMetrics.height + 2 * padY + (relationMetrics?.let { it.height + 2f } ?: 0f)
        val bgRect = Rect.ltrb(
            midPoint.x - width / 2f,
            midPoint.y - height / 2f,
            midPoint.x + width / 2f,
            midPoint.y + height / 2f,
        )
        out += DrawCommand.FillRect(rect = bgRect, color = bgColor, corner = 6f, z = 4)
        out += DrawCommand.StrokeRect(rect = bgRect, stroke = Stroke.Hairline, color = borderColor, corner = 6f, z = 5)
        out += DrawCommand.DrawText(
            text = cardinality,
            origin = Point(midPoint.x, bgRect.top + padY),
            font = flagFont,
            color = textColor,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Top,
            z = 6,
        )
        if (relationMetrics != null && relation.isNotBlank()) {
            out += DrawCommand.DrawText(
                text = relation,
                origin = Point(midPoint.x, bgRect.top + padY + cardMetrics.height + 2f),
                font = relationFont,
                color = textColor,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Top,
                z = 6,
            )
        }
    }

    private fun fontForNode(node: Node): FontSpec = if (isAttributeNode(node)) attributeFont else entityFont

    private fun isAttributeNode(node: Node): Boolean =
        node.payload[MermaidErParser.ER_KIND_KEY] == MermaidErParser.ER_ATTRIBUTE_KIND

    private fun attributeFlagsOf(node: Node): List<String> =
        node.payload[MermaidErParser.ER_ATTRIBUTE_FLAGS_KEY]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun colorOf(value: com.hrm.diagram.core.ir.ArgbColor?, fallback: Color): Color =
        value?.let { Color(it.argb) } ?: fallback

    private fun arrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val ux = dx / len; val uy = dy / len
        val size = 8f
        val baseX = to.x - ux * size; val baseY = to.y - uy * size
        val nx = -uy; val ny = ux
        val p1 = Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
        val p2 = Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
        val path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(p1), PathOp.LineTo(p2), PathOp.Close))
        return DrawCommand.FillPath(path = path, color = color, z = 1)
    }
}
