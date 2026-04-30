package com.hrm.diagram.render.streaming.plantuml

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
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.ClusterStyle
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.parser.plantuml.PlantUmlUsecaseParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.sqrt

internal class PlantUmlUsecaseSubPipeline(
    private val textMeasurer: TextMeasurer,
) : PlantUmlSubPipeline {
    private data class UsecasePalette(
        val actorFill: ArgbColor?,
        val actorStroke: ArgbColor?,
        val actorText: ArgbColor?,
        val usecaseFill: ArgbColor?,
        val usecaseStroke: ArgbColor?,
        val usecaseText: ArgbColor?,
        val noteFill: ArgbColor?,
        val noteStroke: ArgbColor?,
        val noteText: ArgbColor?,
        val rectangleFill: ArgbColor?,
        val rectangleStroke: ArgbColor?,
        val packageFill: ArgbColor?,
        val packageStroke: ArgbColor?,
        val edgeColor: ArgbColor?,
    )

    private val parser = PlantUmlUsecaseParser()
    private val nodeSizes: MutableMap<NodeId, Size> = HashMap()
    private val layout: IncrementalLayout<GraphIR> = SugiyamaLayouts.forGraph(
        defaultNodeSize = Size(180f, 92f),
        nodeSizeOf = { id -> nodeSizes[id] ?: Size(180f, 92f) },
    )
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val clusterFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)

    override fun acceptLine(line: String): IrPatchBatch = parser.acceptLine(line)

    override fun finish(blockClosed: Boolean): IrPatchBatch = parser.finish(blockClosed)

    override fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): PlantUmlRenderState {
        val ir = applyPalette(parser.snapshot())
        measureNodes(ir)
        val baseLaid = layout.layout(
            previousSnapshot.laidOut,
            ir,
            LayoutOptions(direction = ir.styleHints.direction, incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val clusterRects = LinkedHashMap<NodeId, Rect>()
        for (cluster in ir.clusters) computeClusterRect(cluster, baseLaid.nodePositions, clusterRects)
        val bounds = computeBounds(baseLaid.nodePositions.values + clusterRects.values)
        val laidOut = applyAnchoredNotes(
            ir,
            baseLaid.copy(clusterRects = clusterRects, bounds = bounds, seq = seq),
        )
        return PlantUmlRenderState(
            ir = ir,
            laidOut = laidOut,
            drawCommands = render(ir, laidOut),
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    override fun dispose() {
        nodeSizes.clear()
    }

    private fun measureNodes(ir: GraphIR) {
        for (node in ir.nodes) {
            val label = labelTextOf(node)
            when (node.payload[PlantUmlUsecaseParser.KIND_KEY]) {
                "actor" -> {
                    val metrics = textMeasurer.measure(label, labelFont, maxWidth = 140f)
                    nodeSizes[node.id] = Size(
                        width = (metrics.width + 28f).coerceAtLeast(72f),
                        height = (metrics.height + 96f).coerceAtLeast(116f),
                    )
                }
                "note" -> {
                    val metrics = textMeasurer.measure(label, labelFont, maxWidth = 180f)
                    nodeSizes[node.id] = Size(
                        width = (metrics.width + 30f).coerceAtLeast(120f),
                        height = (metrics.height + 24f).coerceAtLeast(54f),
                    )
                }
                else -> {
                    val metrics = textMeasurer.measure(label, labelFont, maxWidth = 180f)
                    nodeSizes[node.id] = Size(
                        width = (metrics.width + 56f).coerceAtLeast(132f),
                        height = (metrics.height + 36f).coerceAtLeast(72f),
                    )
                }
            }
        }
    }

    private fun computeClusterRect(
        cluster: Cluster,
        nodePositions: Map<NodeId, Rect>,
        out: LinkedHashMap<NodeId, Rect>,
    ): Rect? {
        val childRects = cluster.children.mapNotNull { nodePositions[it] }.toMutableList()
        childRects += cluster.nestedClusters.mapNotNull { computeClusterRect(it, nodePositions, out) }
        if (childRects.isEmpty()) return null
        val (_, title) = parseClusterLabel(cluster)
        val titleMetrics = textMeasurer.measure(title.ifBlank { cluster.id.value }, clusterFont, maxWidth = 220f)
        val rect = Rect.ltrb(
            childRects.minOf { it.left } - 22f,
            childRects.minOf { it.top } - (titleMetrics.height + 24f),
            childRects.maxOf { it.right } + 22f,
            childRects.maxOf { it.bottom } + 18f,
        )
        out[cluster.id] = rect
        return rect
    }

    private fun computeBounds(rects: Collection<Rect>): Rect {
        if (rects.isEmpty()) return Rect.ltrb(0f, 0f, 400f, 240f)
        return Rect.ltrb(
            rects.minOf { it.left }.coerceAtMost(0f),
            rects.minOf { it.top }.coerceAtMost(0f),
            rects.maxOf { it.right } + 20f,
            rects.maxOf { it.bottom } + 20f,
        )
    }

    private fun render(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laidOut.bounds
        out += DrawCommand.FillRect(Rect(Point(bounds.left, bounds.top), Size(bounds.size.width, bounds.size.height)), Color(0xFFFFFFFF.toInt()), z = 0)
        for (cluster in ir.clusters) drawCluster(cluster, laidOut.clusterRects, out)
        for (node in ir.nodes) drawNode(node, laidOut, out)
        for ((index, route) in laidOut.edgeRoutes.withIndex()) {
            val edge = ir.edges.getOrNull(index) ?: continue
            drawEdge(edge, route, out)
        }
        return out
    }

    private fun drawCluster(cluster: Cluster, clusterRects: Map<NodeId, Rect>, out: MutableList<DrawCommand>) {
        val rect = clusterRects[cluster.id] ?: return
        val fill = cluster.style.fill?.let { Color(it.argb) } ?: Color(0xFFF5F5F5.toInt())
        val strokeColor = cluster.style.stroke?.let { Color(it.argb) } ?: Color(0xFF78909C.toInt())
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 12f, z = 0)
        out += DrawCommand.StrokeRect(rect = rect, stroke = Stroke(width = cluster.style.strokeWidth ?: 1.5f), color = strokeColor, corner = 12f, z = 1)
        val (kind, title) = parseClusterLabel(cluster)
        out += DrawCommand.DrawText(
            text = "${kind.uppercase()}  ${title.ifBlank { cluster.id.value }}",
            origin = Point(rect.left + 12f, rect.top + 8f),
            font = clusterFont,
            color = strokeColor,
            maxWidth = rect.size.width - 24f,
            anchorX = TextAnchorX.Start,
            anchorY = TextAnchorY.Top,
            z = 2,
        )
        for (nested in cluster.nestedClusters) drawCluster(nested, clusterRects, out)
    }

    private fun drawNode(node: Node, laidOut: LaidOutDiagram, out: MutableList<DrawCommand>) {
        val rect = laidOut.nodePositions[node.id] ?: return
        when (node.payload[PlantUmlUsecaseParser.KIND_KEY]) {
            "actor" -> drawActor(node, rect, out)
            "note" -> drawNote(node, rect, out)
            else -> drawUsecase(node, rect, out)
        }
    }

    private fun drawActor(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) }
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF455A64.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF263238.toInt())
        val stroke = Stroke(width = node.style.strokeWidth ?: 1.5f)
        val cx = (rect.left + rect.right) / 2f
        val top = rect.top + 8f
        val headRadius = 11f
        val headRect = Rect.ltrb(cx - headRadius, top, cx + headRadius, top + headRadius * 2f)
        fill?.let { out += DrawCommand.FillRect(rect = headRect, color = it, corner = headRadius, z = 2) }
        out += DrawCommand.StrokeRect(rect = headRect, stroke = stroke, color = strokeColor, corner = headRadius, z = 3)
        val bodyTop = headRect.bottom
        val bodyBottom = rect.bottom - 28f
        out += DrawCommand.StrokePath(
            path = PathCmd(
                listOf(
                    PathOp.MoveTo(Point(cx, bodyTop)),
                    PathOp.LineTo(Point(cx, bodyTop + 30f)),
                    PathOp.MoveTo(Point(cx - 16f, bodyTop + 12f)),
                    PathOp.LineTo(Point(cx + 16f, bodyTop + 12f)),
                    PathOp.MoveTo(Point(cx, bodyTop + 30f)),
                    PathOp.LineTo(Point(cx - 14f, bodyBottom)),
                    PathOp.MoveTo(Point(cx, bodyTop + 30f)),
                    PathOp.LineTo(Point(cx + 14f, bodyBottom)),
                ),
            ),
            stroke = stroke,
            color = strokeColor,
            z = 3,
        )
        if (node.payload[PlantUmlUsecaseParser.ACTOR_VARIANT_KEY] == "business") {
            out += DrawCommand.StrokePath(
                path = PathCmd(
                    listOf(
                        PathOp.MoveTo(Point(cx - 12f, bodyTop + 2f)),
                        PathOp.LineTo(Point(cx + 12f, bodyTop + 28f)),
                    ),
                ),
                stroke = Stroke(width = 1.25f),
                color = strokeColor,
                z = 4,
            )
        }
        out += DrawCommand.DrawText(
            text = labelTextOf(node),
            origin = Point(cx, rect.bottom - 12f),
            font = labelFont,
            color = textColor,
            maxWidth = rect.size.width - 12f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Bottom,
            z = 4,
        )
    }

    private fun drawUsecase(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFE3F2FD.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFF1565C0.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF0D47A1.toInt())
        val stroke = Stroke(width = node.style.strokeWidth ?: 1.5f)
        val path = ellipsePath(rect)
        out += DrawCommand.FillPath(path = path, color = fill, z = 3)
        out += DrawCommand.StrokePath(path = path, stroke = stroke, color = strokeColor, z = 4)
        out += DrawCommand.DrawText(
            text = labelTextOf(node),
            origin = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
            font = labelFont,
            color = textColor,
            maxWidth = rect.size.width - 20f,
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Middle,
            z = 5,
        )
    }

    private fun drawNote(node: Node, rect: Rect, out: MutableList<DrawCommand>) {
        val fill = node.style.fill?.let { Color(it.argb) } ?: Color(0xFFFFF8E1.toInt())
        val strokeColor = node.style.stroke?.let { Color(it.argb) } ?: Color(0xFFFFA000.toInt())
        val textColor = node.style.textColor?.let { Color(it.argb) } ?: Color(0xFF5D4037.toInt())
        val stroke = Stroke(width = node.style.strokeWidth ?: 1.25f)
        out += DrawCommand.FillRect(rect = rect, color = fill, corner = 8f, z = 3)
        out += DrawCommand.StrokeRect(rect = rect, stroke = stroke, color = strokeColor, corner = 8f, z = 4)
        val fold = 14f
        out += DrawCommand.StrokePath(
            path = PathCmd(
                listOf(
                    PathOp.MoveTo(Point(rect.right - fold, rect.top)),
                    PathOp.LineTo(Point(rect.right - fold, rect.top + fold)),
                    PathOp.LineTo(Point(rect.right, rect.top + fold)),
                ),
            ),
            stroke = Stroke(width = 1.2f),
            color = strokeColor,
            z = 5,
        )
        out += DrawCommand.DrawText(
            text = labelTextOf(node),
            origin = Point(rect.left + 12f, rect.top + 10f),
            font = labelFont,
            color = textColor,
            maxWidth = rect.size.width - 24f,
            anchorX = TextAnchorX.Start,
            anchorY = TextAnchorY.Top,
            z = 6,
        )
    }

    private fun ellipsePath(rect: Rect): PathCmd {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val rx = rect.size.width / 2f
        val ry = rect.size.height / 2f
        val c = 0.55228475f
        return PathCmd(
            listOf(
                PathOp.MoveTo(Point(cx + rx, cy)),
                PathOp.CubicTo(Point(cx + rx, cy + ry * c), Point(cx + rx * c, cy + ry), Point(cx, cy + ry)),
                PathOp.CubicTo(Point(cx - rx * c, cy + ry), Point(cx - rx, cy + ry * c), Point(cx - rx, cy)),
                PathOp.CubicTo(Point(cx - rx, cy - ry * c), Point(cx - rx * c, cy - ry), Point(cx, cy - ry)),
                PathOp.CubicTo(Point(cx + rx * c, cy - ry), Point(cx + rx, cy - ry * c), Point(cx + rx, cy)),
                PathOp.Close,
            ),
        )
    }

    private fun drawEdge(edge: com.hrm.diagram.core.ir.Edge, route: com.hrm.diagram.layout.EdgeRoute, out: MutableList<DrawCommand>) {
        val pts = route.points
        if (pts.size < 2) return
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
        val edgeColor = edge.style.color?.let { Color(it.argb) } ?: Color(0xFF546E7A.toInt())
        val stroke = Stroke(width = edge.style.width ?: 1.5f, dash = edge.style.dash)
        out += DrawCommand.StrokePath(path = PathCmd(ops), stroke = stroke, color = edgeColor, z = 1)
        val headTail = pts[pts.size - 2]
        val head = pts.last()
        val startTail = pts[1]
        val start = pts[0]
        when (edge.arrow) {
            com.hrm.diagram.core.ir.ArrowEnds.None -> Unit
            com.hrm.diagram.core.ir.ArrowEnds.ToOnly -> out += openArrowHead(headTail, head, edgeColor)
            com.hrm.diagram.core.ir.ArrowEnds.FromOnly -> out += openArrowHead(startTail, start, edgeColor)
            com.hrm.diagram.core.ir.ArrowEnds.Both -> {
                out += openArrowHead(headTail, head, edgeColor)
                out += openArrowHead(startTail, start, edgeColor)
            }
        }
        val text = (edge.label as? RichLabel.Plain)?.text ?: return
        if (text.isEmpty()) return
        val mid = pts[pts.size / 2]
        out += DrawCommand.DrawText(
            text = text,
            origin = Point(mid.x, mid.y - 4f),
            font = edgeLabelFont,
            color = Color(0xFF263238.toInt()),
            anchorX = TextAnchorX.Center,
            anchorY = TextAnchorY.Bottom,
            z = 5,
        )
    }

    private fun openArrowHead(from: Point, to: Point, color: Color): DrawCommand {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(to), PathOp.LineTo(to))),
            stroke = Stroke(width = 1f),
            color = color,
            z = 4,
        )
        val ux = dx / len
        val uy = dy / len
        val size = 8f
        val baseX = to.x - ux * size
        val baseY = to.y - uy * size
        val nx = -uy
        val ny = ux
        val p1 = Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
        val p2 = Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
        return DrawCommand.StrokePath(
            path = PathCmd(listOf(PathOp.MoveTo(p1), PathOp.LineTo(to), PathOp.LineTo(p2))),
            stroke = Stroke(width = 1.5f),
            color = color,
            z = 4,
        )
    }

    private fun labelTextOf(node: Node): String =
        when (val label = node.label) {
            is RichLabel.Plain -> label.text.takeIf { it.isNotEmpty() } ?: node.id.value
            is RichLabel.Markdown -> label.source.takeIf { it.isNotEmpty() } ?: node.id.value
            is RichLabel.Html -> label.html.takeIf { it.isNotEmpty() } ?: node.id.value
        }

    private fun parseClusterLabel(cluster: Cluster): Pair<String, String> {
        val text = (cluster.label as? RichLabel.Plain)?.text ?: return "package" to cluster.id.value
        val parts = text.split('\n', limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "package" to text
    }

    private fun applyPalette(ir: GraphIR): GraphIR {
        val palette = paletteOf(ir)
        val nodes = ir.nodes.map { node ->
            when (node.payload[PlantUmlUsecaseParser.KIND_KEY]) {
                "actor" -> node.copy(
                    style = node.style.copy(
                        fill = palette.actorFill ?: node.style.fill,
                        stroke = palette.actorStroke ?: node.style.stroke,
                        textColor = palette.actorText ?: node.style.textColor,
                    ),
                )
                "note" -> node.copy(
                    style = node.style.copy(
                        fill = palette.noteFill ?: node.style.fill,
                        stroke = palette.noteStroke ?: node.style.stroke,
                        textColor = palette.noteText ?: node.style.textColor,
                    ),
                )
                else -> node.copy(
                    style = node.style.copy(
                        fill = palette.usecaseFill ?: node.style.fill,
                        stroke = palette.usecaseStroke ?: node.style.stroke,
                        textColor = palette.usecaseText ?: node.style.textColor,
                    ),
                )
            }
        }
        val edges = ir.edges.map { edge ->
            edge.copy(
                style = edge.style.copy(
                    color = when {
                        edge.from.value.contains("__note_") -> palette.noteStroke ?: edge.style.color
                        else -> palette.edgeColor ?: edge.style.color
                    },
                ),
            )
        }
        val clusters = ir.clusters.map { applyClusterPalette(it, palette) }
        return ir.copy(nodes = nodes, edges = edges, clusters = clusters)
    }

    private fun applyClusterPalette(cluster: Cluster, palette: UsecasePalette): Cluster {
        val kind = parseClusterLabel(cluster).first.lowercase()
        val (fill, stroke) = when (kind) {
            "rectangle" -> palette.rectangleFill to palette.rectangleStroke
            else -> palette.packageFill to palette.packageStroke
        }
        return cluster.copy(
            style = ClusterStyle(
                fill = fill ?: cluster.style.fill,
                stroke = stroke ?: cluster.style.stroke,
                strokeWidth = cluster.style.strokeWidth,
            ),
            nestedClusters = cluster.nestedClusters.map { applyClusterPalette(it, palette) },
        )
    }

    private fun paletteOf(ir: GraphIR): UsecasePalette {
        val extras = ir.styleHints.extras
        fun c(key: String): ArgbColor? = extras[key]?.let(::parsePlantUmlColor)
        return UsecasePalette(
            actorFill = c(PlantUmlUsecaseParser.STYLE_ACTOR_FILL_KEY),
            actorStroke = c(PlantUmlUsecaseParser.STYLE_ACTOR_STROKE_KEY),
            actorText = c(PlantUmlUsecaseParser.STYLE_ACTOR_TEXT_KEY),
            usecaseFill = c(PlantUmlUsecaseParser.STYLE_USECASE_FILL_KEY),
            usecaseStroke = c(PlantUmlUsecaseParser.STYLE_USECASE_STROKE_KEY),
            usecaseText = c(PlantUmlUsecaseParser.STYLE_USECASE_TEXT_KEY),
            noteFill = c(PlantUmlUsecaseParser.STYLE_NOTE_FILL_KEY),
            noteStroke = c(PlantUmlUsecaseParser.STYLE_NOTE_STROKE_KEY),
            noteText = c(PlantUmlUsecaseParser.STYLE_NOTE_TEXT_KEY),
            rectangleFill = c(PlantUmlUsecaseParser.STYLE_RECTANGLE_FILL_KEY),
            rectangleStroke = c(PlantUmlUsecaseParser.STYLE_RECTANGLE_STROKE_KEY),
            packageFill = c(PlantUmlUsecaseParser.STYLE_PACKAGE_FILL_KEY),
            packageStroke = c(PlantUmlUsecaseParser.STYLE_PACKAGE_STROKE_KEY),
            edgeColor = c(PlantUmlUsecaseParser.STYLE_EDGE_COLOR_KEY),
        )
    }

    private fun parsePlantUmlColor(text: String): ArgbColor? {
        val raw = text.trim()
        if (!raw.startsWith("#")) return namedColor(raw)
        val hex = raw.removePrefix("#")
        return when (hex.length) {
            3 -> parseHex("FF${hex.map { "$it$it" }.joinToString("")}")
            6 -> parseHex("FF$hex")
            8 -> parseHex(hex)
            else -> null
        }
    }

    private fun parseHex(argb: String): ArgbColor? = argb.toLongOrNull(16)?.let { ArgbColor(it.toInt()) }

    private fun namedColor(name: String): ArgbColor? = when (name.lowercase()) {
        "lightskyblue" -> ArgbColor(0xFF87CEFA.toInt())
        "palegreen" -> ArgbColor(0xFF98FB98.toInt())
        "lightgreen" -> ArgbColor(0xFF90EE90.toInt())
        "lightblue" -> ArgbColor(0xFFADD8E6.toInt())
        "lightyellow" -> ArgbColor(0xFFFFFFE0.toInt())
        "lightgray", "lightgrey" -> ArgbColor(0xFFD3D3D3.toInt())
        "orange" -> ArgbColor(0xFFFFA500.toInt())
        "red" -> ArgbColor(0xFFFF0000.toInt())
        "green" -> ArgbColor(0xFF008000.toInt())
        "blue" -> ArgbColor(0xFF0000FF.toInt())
        "yellow" -> ArgbColor(0xFFFFFF00.toInt())
        "gray", "grey" -> ArgbColor(0xFF808080.toInt())
        "saddlebrown" -> ArgbColor(0xFF8B4513.toInt())
        "silver" -> ArgbColor(0xFFC0C0C0.toInt())
        "peru" -> ArgbColor(0xFFCD853F.toInt())
        "navy" -> ArgbColor(0xFF000080.toInt())
        "ivory" -> ArgbColor(0xFFFFFFF0.toInt())
        else -> null
    }

    private fun applyAnchoredNotes(ir: GraphIR, laidOut: LaidOutDiagram): LaidOutDiagram {
        val noteNodes = ir.nodes.filter { it.payload[PlantUmlUsecaseParser.KIND_KEY] == "note" }
        if (noteNodes.isEmpty()) return laidOut
        val nodePositions = LinkedHashMap(laidOut.nodePositions)
        val edgeRoutes = laidOut.edgeRoutes.toMutableList()
        for (note in noteNodes) {
            val target = note.payload[PlantUmlUsecaseParser.NOTE_TARGET_KEY]?.let(::NodeId) ?: continue
            val placement = note.payload[PlantUmlUsecaseParser.NOTE_PLACEMENT_KEY].orEmpty()
            val targetRect = nodePositions[target] ?: continue
            val noteRect = nodePositions[note.id] ?: continue
            val anchored = anchoredNoteRect(noteRect.size, targetRect, placement)
            nodePositions[note.id] = anchored
            val edgeIndex = ir.edges.indexOfFirst { it.from == note.id && it.to == target }
            if (edgeIndex >= 0) {
                val route = EdgeRoute(
                    from = note.id,
                    to = target,
                    points = anchoredNoteRoute(anchored, targetRect, placement),
                    kind = RouteKind.Polyline,
                )
                if (edgeIndex < edgeRoutes.size) edgeRoutes[edgeIndex] = route else edgeRoutes += route
            }
        }
        val bounds = computeBounds(nodePositions.values + laidOut.clusterRects.values)
        return laidOut.copy(nodePositions = nodePositions, edgeRoutes = edgeRoutes, bounds = bounds)
    }

    private fun anchoredNoteRect(size: Size, targetRect: Rect, placement: String): Rect {
        val gap = 18f
        return when (placement.lowercase()) {
            "left" -> Rect(Point(targetRect.left - size.width - gap, targetRect.top + (targetRect.size.height - size.height) / 2f), size)
            "top" -> Rect(Point(targetRect.left + (targetRect.size.width - size.width) / 2f, targetRect.top - size.height - gap), size)
            "bottom" -> Rect(Point(targetRect.left + (targetRect.size.width - size.width) / 2f, targetRect.bottom + gap), size)
            else -> Rect(Point(targetRect.right + gap, targetRect.top + (targetRect.size.height - size.height) / 2f), size)
        }
    }

    private fun anchoredNoteRoute(noteRect: Rect, targetRect: Rect, placement: String): List<Point> =
        when (placement.lowercase()) {
            "left" -> listOf(
                Point(noteRect.right, (noteRect.top + noteRect.bottom) / 2f),
                Point(targetRect.left, (targetRect.top + targetRect.bottom) / 2f),
            )
            "top" -> listOf(
                Point((noteRect.left + noteRect.right) / 2f, noteRect.bottom),
                Point((targetRect.left + targetRect.right) / 2f, targetRect.top),
            )
            "bottom" -> listOf(
                Point((noteRect.left + noteRect.right) / 2f, noteRect.top),
                Point((targetRect.left + targetRect.right) / 2f, targetRect.bottom),
            )
            else -> listOf(
                Point(noteRect.left, (noteRect.top + noteRect.bottom) / 2f),
                Point(targetRect.right, (targetRect.top + targetRect.bottom) / 2f),
            )
        }
}
