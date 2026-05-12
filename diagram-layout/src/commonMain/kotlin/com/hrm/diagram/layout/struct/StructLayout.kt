package com.hrm.diagram.layout.struct

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram

/**
 * Compact nested-list layout for `StructIR` diagrams such as PlantUML JSON/YAML.
 *
 * Nodes are placed in preorder. Incremental calls keep existing path-derived ids pinned and append
 * newly discovered structure rows below the previous content.
 */
class StructLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : IncrementalLayout<StructIR> {
    private data class Entry(
        val id: NodeId,
        val parent: NodeId?,
        val depth: Int,
        val label: String,
    )

    private val font = FontSpec(family = "monospace", sizeSp = 12f)

    override fun layout(previous: LaidOutDiagram?, model: StructIR, options: LayoutOptions): LaidOutDiagram {
        val entries = flatten(model.root)
        val previousPositions = previous?.nodePositions.orEmpty()
        val nodePositions = LinkedHashMap<NodeId, Rect>()
        val routes = ArrayList<EdgeRoute>()
        val pad = 20f
        val rowGap = 10f
        val indent = 32f
        var y = pad
        var maxRight = pad

        for (entry in entries) {
            val measured = textMeasurer.measure(entry.label, font, maxWidth = 320f)
            val fresh = Rect(
                origin = Point(pad + entry.depth * indent, y),
                size = Size(
                    width = (measured.width + 24f).coerceAtLeast(80f),
                    height = (measured.height + 14f).coerceAtLeast(30f),
                ),
            )
            val rect = if (options.incremental) previousPositions[entry.id] ?: fresh else fresh
            nodePositions[entry.id] = rect
            y = rect.bottom + rowGap
            maxRight = maxOf(maxRight, rect.right)
        }

        for (entry in entries) {
            val parent = entry.parent ?: continue
            val from = nodePositions[parent] ?: continue
            val to = nodePositions[entry.id] ?: continue
            routes += EdgeRoute(
                from = parent,
                to = entry.id,
                points = listOf(
                    Point(from.left + 14f, from.bottom),
                    Point(from.left + 14f, to.top + to.size.height / 2f),
                    Point(to.left, to.top + to.size.height / 2f),
                ),
            )
        }

        val maxBottom = nodePositions.values.maxOfOrNull { it.bottom } ?: pad
        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = routes,
            bounds = Rect.ltrb(0f, 0f, maxRight + pad, maxBottom + pad),
        )
    }

    private fun flatten(root: StructNode): List<Entry> {
        val out = ArrayList<Entry>()
        fun visit(node: StructNode, path: String, parent: NodeId?, depth: Int) {
            val id = NodeId("struct_$path")
            out += Entry(id = id, parent = parent, depth = depth, label = labelFor(node))
            when (node) {
                is StructNode.ArrayNode -> node.items.forEachIndexed { index, child ->
                    visit(child, "$path.$index", id, depth + 1)
                }
                is StructNode.ObjectNode -> node.entries.forEachIndexed { index, child ->
                    visit(child, "$path.$index", id, depth + 1)
                }
                is StructNode.Scalar -> Unit
            }
        }
        visit(root, "root", null, 0)
        return out
    }

    private fun labelFor(node: StructNode): String {
        val prefix = node.key?.let { "$it: " }.orEmpty()
        return when (node) {
            is StructNode.ArrayNode -> "$prefix[${node.items.size}]"
            is StructNode.ObjectNode -> "$prefix{${node.entries.size}}"
            is StructNode.Scalar -> "$prefix${node.value}"
        }
    }
}
