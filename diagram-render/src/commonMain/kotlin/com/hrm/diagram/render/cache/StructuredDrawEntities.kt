package com.hrm.diagram.render.cache

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.ir.GaugeIR
import com.hrm.diagram.core.ir.GitGraphIR
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.ir.KanbanIR
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.QuadrantChartIR
import com.hrm.diagram.core.ir.SankeyIR
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.WireframeIR
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.layout.LaidOutDiagram

/**
 * Builds IR-structured draw entities for renderers that emit a flat command list internally.
 *
 * Native renderers should still emit exact node/edge/message [DrawEntity] groups when they know
 * ownership. This adapter is the language-pipeline boundary that guarantees stable IR-derived
 * keys instead of command-semantic hashes.
 */
internal fun structuredDrawEntities(
    prefix: String,
    model: DiagramModel?,
    laidOut: LaidOutDiagram?,
    commands: List<DrawCommand>,
): List<DrawEntity> {
    if (commands.isEmpty()) return emptyList()
    val keys = structuralKeys(prefix, model, laidOut)
    val buckets = LinkedHashMap<String, MutableList<DrawCommand>>()
    val anchors = entityAnchors(prefix, laidOut)
    val fallbackKey = "${prefix.normalizedPrefix()}.frame.${model.familyName()}"
    val fallbackKeys = keys.ifEmpty { listOf(fallbackKey) }
    for ((index, command) in commands.withIndex()) {
        val key = anchors.bestKeyFor(command)
            ?: fallbackKeys[index % fallbackKeys.size]
        buckets.getOrPut(key) { ArrayList() } += command
    }
    return buckets.map { (key, groupedCommands) -> DrawEntity(key, groupedCommands) }
}

private fun structuralKeys(prefix: String, model: DiagramModel?, laidOut: LaidOutDiagram?): List<String> {
    val p = prefix.normalizedPrefix()
    val keys = ArrayList<String>()
    when (model) {
        is GraphIR -> {
            model.clusters.flattenClusters().forEach { keys += DrawEntityKey.cluster(p, it.id) }
            model.edges.forEachIndexed { index, edge -> keys += DrawEntityKey.edge(p, edge.from, edge.to, index) }
            model.nodes.forEach { keys += DrawEntityKey.node(p, it.id) }
        }
        is SequenceIR -> {
            model.participants.forEach { keys += "$p.participant.${it.id.value}" }
            model.messages.forEachIndexed { index, message ->
                keys += "$p.message.$index.${message.from.value}->${message.to.value}"
            }
            model.fragments.forEachIndexed { index, fragment -> keys += "$p.fragment.$index.${fragment.kind}" }
        }
        is TimeSeriesIR -> {
            model.tracks.forEach { keys += "$p.time.track.${it.id.value}" }
            model.items.forEach { keys += "$p.time.item.${it.id.value}" }
        }
        is TreeIR -> model.root.flattenTree().forEach { keys += "$p.tree.node.${it.id.value}" }
        is JourneyIR -> model.stages.forEachIndexed { stageIndex, stage ->
            keys += "$p.journey.stage.$stageIndex"
            stage.steps.forEachIndexed { stepIndex, _ -> keys += "$p.journey.step.$stageIndex.$stepIndex" }
        }
        is PieIR -> model.slices.forEachIndexed { index, _ -> keys += "$p.pie.slice.$index" }
        is GaugeIR -> keys += "$p.gauge.value"
        is KanbanIR -> model.columns.forEach { column ->
            keys += "$p.kanban.column.${column.id.value}"
            column.cards.forEach { card -> keys += "$p.kanban.card.${card.id.value}" }
        }
        is XYChartIR -> model.series.forEachIndexed { seriesIndex, series ->
            keys += "$p.xy.series.$seriesIndex.${series.name.stableSegment()}"
            series.xs.indices.forEach { pointIndex -> keys += "$p.xy.point.$seriesIndex.$pointIndex" }
        }
        is QuadrantChartIR -> model.points.forEach { keys += "$p.quadrant.point.${it.id.value}" }
        is SankeyIR -> {
            model.nodes.forEach { keys += DrawEntityKey.node(p, it.id) }
            model.flows.forEachIndexed { index, flow -> keys += "$p.sankey.flow.$index.${flow.from.value}->${flow.to.value}" }
        }
        is GitGraphIR -> {
            model.branches.forEach { keys += "$p.git.branch.${it.stableSegment()}" }
            model.commits.forEach { keys += "$p.git.commit.${it.id.value}" }
        }
        is ClassIR -> {
            model.namespaces.forEach { keys += "$p.class.namespace.${it.id.stableSegment()}" }
            model.relations.forEachIndexed { index, relation -> keys += "$p.class.relation.$index.${relation.from.value}->${relation.to.value}" }
            model.classes.forEach { keys += "$p.class.node.${it.id.value}" }
            model.notes.forEachIndexed { index, _ -> keys += "$p.class.note.$index" }
        }
        is StateIR -> {
            model.transitions.forEachIndexed { index, transition -> keys += "$p.state.transition.$index.${transition.from.value}->${transition.to.value}" }
            model.states.forEach { keys += "$p.state.node.${it.id.value}" }
            model.notes.forEachIndexed { index, _ -> keys += "$p.state.note.$index" }
        }
        is WireframeIR -> keys += "$p.wireframe.root"
        is StructIR -> keys += "$p.struct.root"
        null -> Unit
        else -> keys += "$p.${model.familyName()}.frame"
    }
    laidOut?.nodePositions?.keys?.forEach { id ->
        val key = DrawEntityKey.node(p, id)
        if (key !in keys) keys += key
    }
    laidOut?.clusterRects?.keys?.forEach { id ->
        val key = DrawEntityKey.cluster(p, id)
        if (key !in keys) keys += key
    }
    laidOut?.layoutState?.edgeRoutesByKey?.keys?.forEach { key ->
        val entityKey = DrawEntityKey.edge(p, key)
        if (entityKey !in keys) keys += entityKey
    }
    return keys
}

private data class EntityAnchor(
    val key: String,
    val bounds: Rect,
    val priority: Int,
)

private fun entityAnchors(prefix: String, laidOut: LaidOutDiagram?): List<EntityAnchor> {
    if (laidOut == null) return emptyList()
    val p = prefix.normalizedPrefix()
    val anchors = ArrayList<EntityAnchor>()
    laidOut.clusterRects.forEach { (id, rect) ->
        anchors += EntityAnchor(DrawEntityKey.cluster(p, id), rect, priority = 0)
    }
    laidOut.layoutState.edgeRoutesByKey.forEach { (key, route) ->
        anchors += EntityAnchor(DrawEntityKey.edge(p, key), route.points.bounds().expand(6f), priority = 1)
    }
    laidOut.nodePositions.forEach { (id, rect) ->
        anchors += EntityAnchor(DrawEntityKey.node(p, id), rect, priority = 2)
    }
    return anchors
}

private fun List<EntityAnchor>.bestKeyFor(command: DrawCommand): String? {
    val bounds = commandBounds(command) ?: return null
    var best: EntityAnchor? = null
    var bestScore = 0f
    for (anchor in this) {
        val score = bounds.overlapArea(anchor.bounds) * 10f + if (anchor.bounds.contains(bounds.center)) 1f else 0f
        if (score > bestScore || (score == bestScore && best != null && anchor.priority > best.priority)) {
            best = anchor
            bestScore = score
        }
    }
    return best?.takeIf { bestScore > 0f }?.key
}

private fun List<Point>.bounds(): Rect {
    var minX = first().x
    var maxX = first().x
    var minY = first().y
    var maxY = first().y
    for (point in drop(1)) {
        if (point.x < minX) minX = point.x
        if (point.x > maxX) maxX = point.x
        if (point.y < minY) minY = point.y
        if (point.y > maxY) maxY = point.y
    }
    return Rect.ltrb(minX, minY, maxX, maxY)
}

private val Rect.center: Point
    get() = Point((left + right) / 2f, (top + bottom) / 2f)

private fun Rect.expand(padding: Float): Rect =
    Rect.ltrb(left - padding, top - padding, right + padding, bottom + padding)

private fun Rect.overlapArea(other: Rect): Float {
    val overlapLeft = maxOf(left, other.left)
    val overlapTop = maxOf(top, other.top)
    val overlapRight = minOf(right, other.right)
    val overlapBottom = minOf(bottom, other.bottom)
    val width = overlapRight - overlapLeft
    val height = overlapBottom - overlapTop
    return if (width > 0f && height > 0f) width * height else 0f
}

private fun Rect.contains(point: Point): Boolean =
    point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

private fun List<com.hrm.diagram.core.ir.Cluster>.flattenClusters(): List<com.hrm.diagram.core.ir.Cluster> =
    flatMap { cluster -> listOf(cluster) + cluster.nestedClusters.flattenClusters() }

private fun com.hrm.diagram.core.ir.TreeNode.flattenTree(): List<com.hrm.diagram.core.ir.TreeNode> =
    listOf(this) + children.flatMap { it.flattenTree() }

private fun DiagramModel?.familyName(): String =
    when (this) {
        is GraphIR -> "graph"
        is SequenceIR -> "sequence"
        is TimeSeriesIR -> "timeseries"
        is TreeIR -> "tree"
        is JourneyIR -> "journey"
        is PieIR -> "pie"
        is GaugeIR -> "gauge"
        is KanbanIR -> "kanban"
        is XYChartIR -> "xychart"
        is QuadrantChartIR -> "quadrantchart"
        is SankeyIR -> "sankey"
        is GitGraphIR -> "gitgraph"
        is ClassIR -> "class"
        is StateIR -> "state"
        is WireframeIR -> "wireframe"
        is StructIR -> "struct"
        null -> "empty"
        else -> "diagram"
    }

private fun String.normalizedPrefix(): String = stableSegment().ifBlank { "diagram" }

private fun String.stableSegment(): String =
    trim()
        .lowercase()
        .map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch == '.' || ch == '-' || ch == '_' -> ch
                else -> '_'
            }
        }
        .joinToString("")
        .trim('.', '-', '_')
