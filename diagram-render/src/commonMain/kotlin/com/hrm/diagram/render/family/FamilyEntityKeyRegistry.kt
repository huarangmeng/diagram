package com.hrm.diagram.render.family

import com.hrm.diagram.core.ir.ActivityBlock
import com.hrm.diagram.core.ir.ActivityIR
import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.ir.GaugeIR
import com.hrm.diagram.core.ir.GitGraphIR
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.ir.KanbanIR
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.QuadrantChartIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SankeyIR
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.TreeNode
import com.hrm.diagram.core.ir.WireBox
import com.hrm.diagram.core.ir.WireframeIR
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.render.cache.DrawEntityKey

/**
 * Central registry for stable entity keys shared by Mermaid and PlantUML family renderers.
 */
internal object FamilyEntityKeyRegistry {
    fun semanticKeys(prefix: String, model: DiagramModel?): List<String> {
        val p = normalizedPrefix(prefix)
        val keys = ArrayList<String>()
        when (model) {
            null -> keys += DrawEntityKey.decoration(p, "empty", "frame")
            is GraphIR -> {
                flattenClusters(model.clusters).forEach { keys += DrawEntityKey.cluster(p, it.id) }
                model.edges.forEachIndexed { index, edge -> keys += DrawEntityKey.edge(p, edge.from, edge.to, index) }
                model.nodes.forEach { keys += DrawEntityKey.node(p, it.id) }
            }
            is SequenceIR -> {
                model.participants.forEach { keys += "$p.sequence.participant.${stableSegment(it.id.value)}" }
                model.messages.forEachIndexed { index, message -> keys += "$p.sequence.message.$index.${stableSegment(message.from.value)}-${stableSegment(message.to.value)}" }
                model.fragments.forEachIndexed { index, fragment -> keys += "$p.sequence.fragment.$index.${stableSegment(fragment.kind.name)}" }
            }
            is TimeSeriesIR -> {
                keys += DrawEntityKey.decoration(p, "time-series", "axis")
                model.tracks.forEach { track -> keys += "$p.time-series.lane.${stableSegment(track.id.value)}" }
                model.items.forEach { item ->
                    keys += "$p.time-series.item.${stableSegment(item.id.value)}"
                    item.depends.forEach { depends -> keys += "$p.time-series.dependency.${stableSegment(depends.value)}-${stableSegment(item.id.value)}" }
                }
            }
            is TreeIR -> {
                fun visit(node: TreeNode) {
                    keys += "$p.tree.node.${stableSegment(node.id.value)}"
                    node.children.forEach { child ->
                        keys += "$p.tree.edge.${stableSegment(node.id.value)}-${stableSegment(child.id.value)}"
                        visit(child)
                    }
                }
                visit(model.root)
            }
            is JourneyIR -> model.stages.forEachIndexed { stageIndex, stage ->
                keys += "$p.journey.stage.$stageIndex"
                stage.steps.forEachIndexed { stepIndex, step ->
                    keys += "$p.journey.step.$stageIndex.$stepIndex"
                    step.actors.forEachIndexed { actorIndex, _ -> keys += "$p.journey.actor.$stageIndex.$stepIndex.$actorIndex" }
                }
            }
            is PieIR -> {
                keys += DrawEntityKey.decoration(p, "pie", "title")
                model.slices.forEachIndexed { index, slice ->
                    val segment = stableSegment(labelText(slice.label).ifBlank { "slice-$index" })
                    keys += "$p.pie.slice.$index.$segment"
                    keys += "$p.pie.legend.$index.$segment"
                    keys += "$p.pie.label.$index.$segment"
                }
            }
            is GaugeIR -> {
                keys += "$p.gauge.arc"
                keys += "$p.gauge.value"
                keys += "$p.gauge.label"
            }
            is KanbanIR -> model.columns.forEach { column ->
                keys += "$p.kanban.column.${stableSegment(column.id.value)}"
                column.cards.forEach { card -> keys += "$p.kanban.card.${stableSegment(card.id.value)}" }
            }
            is XYChartIR -> {
                keys += "$p.xy.axis.x"
                keys += "$p.xy.axis.y"
                keys += "$p.xy.grid"
                model.series.forEachIndexed { seriesIndex, series ->
                    val seriesKey = stableSegment(series.name.ifBlank { "series-$seriesIndex" })
                    keys += "$p.xy.series.$seriesIndex.$seriesKey"
                    series.xs.indices.forEach { pointIndex -> keys += "$p.xy.point.$seriesIndex.$pointIndex.$seriesKey" }
                }
                keys += "$p.xy.legend"
            }
            is QuadrantChartIR -> {
                (1..4).forEach { keys += "$p.quadrant.area.$it" }
                model.points.forEach { point -> keys += "$p.quadrant.point.${stableSegment(point.id.value)}" }
                keys += "$p.quadrant.axis"
            }
            is SankeyIR -> {
                model.nodes.forEach { node -> keys += "$p.sankey.node.${stableSegment(node.id.value)}" }
                model.flows.forEachIndexed { index, flow -> keys += "$p.sankey.flow.$index.${stableSegment(flow.from.value)}-${stableSegment(flow.to.value)}" }
            }
            is GitGraphIR -> {
                model.branches.forEach { branch -> keys += "$p.git.branch.${stableSegment(branch)}" }
                model.commits.forEach { commit ->
                    keys += "$p.git.commit.${stableSegment(commit.id.value)}"
                    commit.parents.forEach { parent -> keys += "$p.git.edge.${stableSegment(parent.value)}-${stableSegment(commit.id.value)}" }
                }
            }
            is ActivityIR -> model.blocks.forEachIndexed { index, block -> addActivityBlockKeys(keys, p, "activity.$index", block) }
            is WireframeIR -> addWireBoxKeys(keys, p, "wireframe.root", model.root)
            is StructIR -> addStructKeys(keys, p, "struct.root", model.root)
            is StateIR -> {
                model.states.forEach { state -> keys += "$p.state.node.${stableSegment(state.id.value)}" }
                model.transitions.forEachIndexed { index, transition -> keys += "$p.state.transition.$index.${stableSegment(transition.from.value)}-${stableSegment(transition.to.value)}" }
                model.notes.forEachIndexed { index, note -> keys += "$p.state.note.$index.${stableSegment(note.targetState?.value.orEmpty())}" }
            }
            is ClassIR -> {
                model.namespaces.forEach { namespace -> keys += "$p.class.namespace.${stableSegment(namespace.id)}" }
                model.classes.forEach { klass ->
                    keys += "$p.class.node.${stableSegment(klass.id.value)}"
                    klass.members.forEachIndexed { index, member -> keys += "$p.class.member.${stableSegment(klass.id.value)}.$index.${stableSegment(member.name)}" }
                }
                model.relations.forEachIndexed { index, relation -> keys += "$p.class.relation.$index.${stableSegment(relation.from.value)}-${stableSegment(relation.to.value)}" }
                model.notes.forEachIndexed { index, note -> keys += "$p.class.note.$index.${stableSegment(note.targetClass?.value.orEmpty())}" }
            }
        }
        return keys.distinct()
    }

    fun familyKey(model: DiagramModel?): String =
        when (model) {
            null -> "empty"
            is GraphIR -> "graph"
            is SequenceIR -> "sequence"
            is TimeSeriesIR -> "time-series"
            is TreeIR -> "tree"
            is JourneyIR -> "journey"
            is PieIR -> "pie"
            is GaugeIR -> "gauge"
            is KanbanIR -> "kanban"
            is XYChartIR -> "xy-chart"
            is QuadrantChartIR -> "quadrant-chart"
            is SankeyIR -> "sankey"
            is GitGraphIR -> "git-graph"
            is ActivityIR -> "activity"
            is WireframeIR -> "wireframe"
            is StructIR -> "struct"
            is StateIR -> "state"
            is ClassIR -> "class"
        }

    private fun flattenClusters(clusters: List<com.hrm.diagram.core.ir.Cluster>): List<com.hrm.diagram.core.ir.Cluster> =
        clusters.flatMap { cluster -> listOf(cluster) + flattenClusters(cluster.nestedClusters) }

    private fun addActivityBlockKeys(keys: MutableList<String>, prefix: String, path: String, block: ActivityBlock) {
        keys += "$prefix.${stableSegment(path)}.${stableSegment(block::class.simpleName.orEmpty())}"
        when (block) {
            is ActivityBlock.IfElse -> {
                block.thenBranch.forEachIndexed { index, child -> addActivityBlockKeys(keys, prefix, "$path.then.$index", child) }
                block.elseBranch.forEachIndexed { index, child -> addActivityBlockKeys(keys, prefix, "$path.else.$index", child) }
            }
            is ActivityBlock.While -> block.body.forEachIndexed { index, child -> addActivityBlockKeys(keys, prefix, "$path.body.$index", child) }
            is ActivityBlock.ForkJoin -> block.branches.forEachIndexed { branchIndex, branch ->
                branch.forEachIndexed { index, child -> addActivityBlockKeys(keys, prefix, "$path.branch.$branchIndex.$index", child) }
            }
            is ActivityBlock.Action, is ActivityBlock.Note -> Unit
        }
    }

    private fun addWireBoxKeys(keys: MutableList<String>, prefix: String, path: String, box: WireBox) {
        keys += "$prefix.${stableSegment(path)}.${stableSegment(box::class.simpleName.orEmpty())}"
        when (box) {
            is WireBox.Plain -> box.children.forEachIndexed { index, child -> addWireBoxKeys(keys, prefix, "$path.child.$index", child) }
            is WireBox.TabbedGroup -> box.tabs.forEachIndexed { index, child -> addWireBoxKeys(keys, prefix, "$path.tab.$index", child) }
            is WireBox.Button, is WireBox.Image, is WireBox.Input -> Unit
        }
    }

    private fun addStructKeys(keys: MutableList<String>, prefix: String, path: String, node: StructNode) {
        val keySegment = stableSegment(node.key ?: path)
        keys += "$prefix.${stableSegment(path)}.$keySegment"
        when (node) {
            is StructNode.ObjectNode -> node.entries.forEachIndexed { index, child -> addStructKeys(keys, prefix, "$path.object.$index.${child.key.orEmpty()}", child) }
            is StructNode.ArrayNode -> node.items.forEachIndexed { index, child -> addStructKeys(keys, prefix, "$path.array.$index.${child.key.orEmpty()}", child) }
            is StructNode.Scalar -> Unit
        }
    }

    private fun labelText(label: RichLabel): String =
        when (label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
        }

    private fun normalizedPrefix(value: String): String = stableSegment(value).ifBlank { "diagram" }

    private fun stableSegment(value: String): String =
        value
            .trim()
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
}
