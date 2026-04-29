package com.hrm.diagram.layout.gitgraph

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.GitGraphIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind

class GitGraphLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<GitGraphIR> {
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val branchFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 10f)
    private val tagFont = FontSpec(family = "sans-serif", sizeSp = 10f, weight = 600)

    override fun layout(previous: LaidOutDiagram?, model: GitGraphIR, options: LayoutOptions): LaidOutDiagram {
        val nodePositions = LinkedHashMap<com.hrm.diagram.core.ir.NodeId, Rect>()
        val edgeRoutes = ArrayList<EdgeRoute>()
        val pad = 20f
        val branchLeft = 90f
        val topBase = 88f
        val laneGap = 78f
        val commitStep = 84f
        val commitSize = 18f
        var top = pad

        model.title?.takeIf { it.isNotBlank() }?.let { title ->
            val m = textMeasurer.measure(title, titleFont, maxWidth = 960f)
            nodePositions[com.hrm.diagram.core.ir.NodeId("gitgraph:title")] = Rect(Point(pad, top), Size(m.width, m.height))
            top += m.height + 14f
        }
        val laneTop = maxOf(topBase, top)
        val branchIndex = model.branches.withIndex().associate { it.value to it.index }

        for ((idx, branch) in model.branches.withIndex()) {
            val m = textMeasurer.measure(branch, branchFont, maxWidth = 100f)
            nodePositions[com.hrm.diagram.core.ir.NodeId("gitgraph:branch:$branch")] =
                Rect(Point(pad, laneTop + idx * laneGap - m.height / 2f), Size(m.width, m.height))
        }

        for ((idx, commit) in model.commits.withIndex()) {
            val x = branchLeft + idx * commitStep
            val y = laneTop + (branchIndex[commit.branch] ?: 0) * laneGap
            nodePositions[com.hrm.diagram.core.ir.NodeId("gitgraph:commit:${commit.id.value}")] =
                Rect(Point(x - commitSize / 2f, y - commitSize / 2f), Size(commitSize, commitSize))

            val labelText = (commit.label as? RichLabel.Plain)?.text.orEmpty()
            if (labelText.isNotBlank()) {
                val m = textMeasurer.measure(labelText, labelFont, maxWidth = 120f)
                nodePositions[com.hrm.diagram.core.ir.NodeId("gitgraph:label:${commit.id.value}")] =
                    Rect(Point(x - m.width / 2f, y + commitSize / 2f + 8f), Size(m.width, m.height))
            }
            commit.tag?.takeIf { it.isNotBlank() }?.let { tag ->
                val m = textMeasurer.measure(tag, tagFont, maxWidth = 120f)
                nodePositions[com.hrm.diagram.core.ir.NodeId("gitgraph:tag:${commit.id.value}")] =
                    Rect(Point(x - m.width / 2f - 6f, y - commitSize / 2f - m.height - 10f), Size(m.width + 12f, m.height + 6f))
            }
        }

        val commitCenter = model.commits.associate { commit ->
            val rect = nodePositions[com.hrm.diagram.core.ir.NodeId("gitgraph:commit:${commit.id.value}")]!!
            commit.id to Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
        }
        for (commit in model.commits) {
            val target = commitCenter[commit.id] ?: continue
            for (parent in commit.parents) {
                val source = commitCenter[parent] ?: continue
                edgeRoutes += EdgeRoute(from = parent, to = commit.id, points = listOf(source, target), kind = RouteKind.Polyline)
            }
        }

        val width = maxOf(480f, branchLeft + model.commits.size * commitStep + 80f)
        val height = laneTop + model.branches.size.coerceAtLeast(1) * laneGap + 60f
        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = edgeRoutes,
            clusterRects = emptyMap(),
            bounds = Rect.ltrb(0f, 0f, width, height),
        )
    }
}
