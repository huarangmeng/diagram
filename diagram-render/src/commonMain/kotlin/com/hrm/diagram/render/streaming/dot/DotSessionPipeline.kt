package com.hrm.diagram.render.streaming.dot

import com.hrm.diagram.core.draw.ArrowHead
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.parser.dot.DotParser
import com.hrm.diagram.render.graph.GraphClusterLayout
import com.hrm.diagram.render.graph.GraphIrRenderer
import com.hrm.diagram.render.graph.GraphMeasurePolicy
import com.hrm.diagram.render.graph.GraphRenderStyle
import com.hrm.diagram.render.graph.graphLabelText
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPipeline
import com.hrm.diagram.render.streaming.kernel.StreamingGraphPipelineKernel

internal class DotSessionPipeline(
    private val textMeasurer: TextMeasurer,
) : SessionPipeline {
    private val parser = DotParser()
    private val parserSession = parser.incrementalSession()
    private val nodeFont = FontSpec(family = "sans-serif", sizeSp = 12f)
    private val edgeFont = FontSpec(family = "sans-serif", sizeSp = 10f)
    private val clusterFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val measurePolicy = GraphMeasurePolicy(
        textMeasurer = textMeasurer,
        defaultSize = Size(112f, 44f),
        maxWidth = 200f,
        minWidth = 72f,
        minHeight = 38f,
        fontOf = ::fontOf,
        paddingOf = { node ->
            when (node.shape) {
                NodeShape.Diamond -> 30f to 18f
                NodeShape.Circle, NodeShape.Ellipse -> 24f to 12f
                else -> 18f to 12f
            }
        },
    )
    private val renderer = GraphIrRenderer(textMeasurer, dotRenderStyle())
    private val kernel = StreamingGraphPipelineKernel(
        textMeasurer = textMeasurer,
        sourceLanguage = SourceLanguage.DOT,
        measurePolicy = measurePolicy,
        layout = StreamingGraphPipelineKernel.sugiyamaLayout(Size(112f, 44f), measurePolicy),
        renderEntities = renderer::render,
        layoutModel = { ir -> ir.copy(edges = ir.edges.filterNot { it.payload["dot.edge.constraint"].equals("false", ignoreCase = true) }) },
        layoutOptions = { ir, isFinal ->
            LayoutOptions(
                direction = ir.styleHints.direction,
                nodeSpacing = dotSpacing(ir, "dot.graph.nodesep", defaultPx = 24f),
                rankSpacing = dotSpacing(ir, "dot.graph.ranksep", defaultPx = 48f),
                incremental = !isFinal,
                allowGlobalReflow = isFinal,
                extras = ir.styleHints.extras,
            )
        },
        postLayout = { ir, laid -> GraphClusterLayout.withClusterRects(ir, laid, textMeasurer, clusterFont) },
        edgeKeyOf = { index, edge -> "${edge.from.value}->${edge.to.value}:$index:${graphLabelText(edge.label)}" },
    )

    override fun advance(
        previousSnapshot: DiagramSnapshot,
        chunk: CharSequence,
        absoluteOffset: Int,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val result = parserSession.feed(chunk, eos = isFinal)
        return kernel.advance(
            previousSnapshot = previousSnapshot,
            seq = seq,
            isFinal = isFinal,
            ir = result.ir,
            diagnostics = result.diagnostics,
        )
    }

    override fun dispose() {
        parserSession.reset()
        kernel.clear()
    }

    private fun dotRenderStyle(): GraphRenderStyle = GraphRenderStyle(
        prefix = "dot",
        nodeFont = nodeFont,
        edgeFont = edgeFont,
        clusterFont = clusterFont,
        edgeColor = Color(0xFF4B5563.toInt()),
        nodeFontOf = { node, _ -> fontOf(node) },
        nodeTextColorOf = { node, _ -> nodeHtmlColor(node) },
        edgeLabelFontOf = { edge, prefix, _ -> edgeLabelFont(edge, dotLabelPrefix(prefix)) },
        edgeLabelColorOf = { edge, prefix, _ -> edgeLabelColor(edge, dotLabelPrefix(prefix)) },
        arrowHeadOf = { edge, enabled -> arrowHeadOf(edge.payload["dot.edge.arrowhead"], enabled) },
        edgeEndpointAdjuster = { edge, points, laid -> applyPortAnchors(edge, points, laid.nodePositions) },
        graphBackground = { ir, _ -> ir.styleHints.extras["dot.graph.bgcolor"]?.let(::colorOf) },
        hyperlinkOf = { node -> node.payload["dot.node.url"] ?: node.payload["dot.node.href"] },
    )

    private fun fontOf(node: Node): FontSpec =
        FontSpec(
            family = node.payload["dot.node.html.fontname"]?.takeIf { it.isNotBlank() }
                ?: node.payload["dot.node.fontname"]?.takeIf { it.isNotBlank() }
                ?: nodeFont.family,
            sizeSp = node.payload["dot.node.html.fontsize"]?.toFloatOrNull()?.coerceIn(6f, 96f)
                ?: node.payload["dot.node.fontsize"]?.toFloatOrNull()?.coerceIn(6f, 96f)
                ?: nodeFont.sizeSp,
            weight = if (
                node.payload["dot.node.html.bold"].toBoolean()
                || node.payload["dot.node.style"]?.contains("bold", ignoreCase = true) == true
            ) 700 else nodeFont.weight,
            italic = node.payload["dot.node.html.italic"].toBoolean()
                || node.payload["dot.node.style"]?.contains("italic", ignoreCase = true) == true,
        )

    private fun nodeHtmlColor(node: Node): Color? =
        node.payload["dot.node.html.fontcolor"]?.let(::colorOf)

    private fun edgeLabelFont(edge: Edge, prefix: String): FontSpec =
        FontSpec(
            family = edge.payload["$prefix.html.fontname"]?.takeIf { it.isNotBlank() }
                ?: edge.payload["$prefix.fontname"]?.takeIf { it.isNotBlank() }
                ?: edgeFont.family,
            sizeSp = edge.payload["$prefix.html.fontsize"]?.toFloatOrNull()?.coerceIn(6f, 96f)
                ?: edge.payload["$prefix.fontsize"]?.toFloatOrNull()?.coerceIn(6f, 96f)
                ?: edgeFont.sizeSp,
            weight = if (edge.payload["$prefix.html.bold"].toBoolean() || edge.payload["$prefix.bold"].toBoolean()) 700 else edgeFont.weight,
            italic = edge.payload["$prefix.html.italic"].toBoolean() || edge.payload["$prefix.italic"].toBoolean(),
        )

    private fun edgeLabelColor(edge: Edge, prefix: String): Color? =
        edge.payload["$prefix.html.fontcolor"]?.let(::colorOf) ?: edge.payload["$prefix.fontcolor"]?.let(::colorOf)

    private fun dotLabelPrefix(prefix: String): String =
        if (prefix == "edge") "dot.edge" else prefix

    private fun applyPortAnchors(edge: Edge, points: List<Point>, nodes: Map<NodeId, Rect>): List<Point> {
        if (points.size < 2) return points
        val out = points.toMutableList()
        nodes[edge.from]?.let { from ->
            out[0] = anchorFor(from, edge.payload["dot.edge.fromCompass"] ?: edge.payload["dot.edge.fromPort"]) ?: out[0]
        }
        nodes[edge.to]?.let { to ->
            out[out.lastIndex] = anchorFor(to, edge.payload["dot.edge.toCompass"] ?: edge.payload["dot.edge.toPort"]) ?: out.last()
        }
        return out
    }

    private fun anchorFor(rect: Rect, raw: String?): Point? {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        return when (raw?.lowercase()) {
            "n" -> Point(cx, rect.top)
            "ne" -> Point(rect.right, rect.top)
            "e" -> Point(rect.right, cy)
            "se" -> Point(rect.right, rect.bottom)
            "s" -> Point(cx, rect.bottom)
            "sw" -> Point(rect.left, rect.bottom)
            "w" -> Point(rect.left, cy)
            "nw" -> Point(rect.left, rect.top)
            "c", "_" -> Point(cx, cy)
            else -> null
        }
    }

    private fun arrowHeadOf(raw: String?, enabled: Boolean): ArrowHead {
        if (!enabled) return ArrowHead.None
        return when (raw?.lowercase()?.substringBefore(':')) {
            null, "", "normal", "vee" -> ArrowHead.Triangle
            "none" -> ArrowHead.None
            "empty" -> ArrowHead.OpenTriangle
            "diamond" -> ArrowHead.Diamond
            "odiamond" -> ArrowHead.OpenDiamond
            "dot" -> ArrowHead.Circle
            "odot" -> ArrowHead.OpenCircle
            "tee" -> ArrowHead.Bar
            "crow" -> ArrowHead.Cross
            else -> ArrowHead.Triangle
        }
    }

    private fun dotSpacing(ir: GraphIR, key: String, defaultPx: Float): Float {
        val inches = ir.styleHints.extras[key]?.toFloatOrNull() ?: return defaultPx
        return (inches * 72f).coerceIn(8f, 240f)
    }

    private fun colorOf(raw: String): Color? {
        val value = raw.trim().removeSurrounding("\"")
        val hex = value.removePrefix("#")
        if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return Color((0xFF000000 or hex.toLong(16)).toInt())
        }
        return when (value.lowercase()) {
            "black" -> Color.Black
            "white" -> Color.White
            "red" -> Color(0xFFE53935.toInt())
            "green" -> Color(0xFF43A047.toInt())
            "blue" -> Color(0xFF1E88E5.toInt())
            "yellow" -> Color(0xFFFDD835.toInt())
            "orange" -> Color(0xFFFF9800.toInt())
            "purple" -> Color(0xFF8E24AA.toInt())
            "gray", "grey" -> Color(0xFF9E9E9E.toInt())
            else -> null
        }
    }
}
