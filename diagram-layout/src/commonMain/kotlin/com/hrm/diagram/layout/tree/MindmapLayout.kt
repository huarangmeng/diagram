package com.hrm.diagram.layout.tree

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.TreeNode
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.math.max

/**
 * Deterministic tidy-tree style layout for Mermaid mindmap / WBS trees.
 *
 * The layout is intentionally simple:
 * - root centered
 * - first-level branches distributed to both sides
 * - siblings stacked vertically
 * - subtree height determines parent centering
 *
 * Incremental mode keeps existing node rects pinned and only allocates fresh rects for new ids.
 */
class MindmapLayout(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) {
    private companion object {
        const val MINDMAP_SIDE_KEY = "plantuml.mindmap.side"
        const val MINDMAP_FONT_SIZE_KEY = "plantuml.mindmap.styleFontSize"
        const val MINDMAP_MAXIMUM_WIDTH_KEY = "plantuml.mindmap.styleMaximumWidth"
        const val WBS_SIDE_KEY = "plantuml.wbs.side"
        const val WBS_FONT_SIZE_KEY = "plantuml.wbs.styleFontSize"
        const val WBS_MAXIMUM_WIDTH_KEY = "plantuml.wbs.styleMaximumWidth"
    }

    private val font = FontSpec(family = "sans-serif", sizeSp = 12f)

    fun layout(previous: LaidOutDiagram?, model: TreeIR, options: LayoutOptions): LaidOutDiagram {
        val prevPos = previous?.nodePositions.orEmpty()
        val nodePositions = LinkedHashMap<NodeId, Rect>()
        val pad = 20f
        val colGap = 56f
        val rowGap = 18f

        val measured = HashMap<NodeId, Size>()
        val fontSizes = parseNodeFloatMap(
            model.styleHints.extras[MINDMAP_FONT_SIZE_KEY]
                ?: model.styleHints.extras[WBS_FONT_SIZE_KEY].orEmpty(),
        )
        val maximumWidths = parseNodeFloatMap(
            model.styleHints.extras[MINDMAP_MAXIMUM_WIDTH_KEY]
                ?: model.styleHints.extras[WBS_MAXIMUM_WIDTH_KEY].orEmpty(),
        )
        fun measure(n: TreeNode): Size {
            return measured.getOrPut(n.id) {
                val text = (n.label as? RichLabel.Plain)?.text ?: ""
                val nodeFont = fontSizes[n.id]?.let { font.copy(sizeSp = it) } ?: font
                val maxWidth = maximumWidths[n.id]?.coerceIn(72f, 360f) ?: 180f
                val m = textMeasurer.measure(text, nodeFont, maxWidth = maxWidth)
                Size(
                    width = (m.width + 28f).coerceAtLeast(56f).coerceAtMost(maxWidth + 28f),
                    height = (m.height + 20f).coerceAtLeast(34f),
                )
            }
        }

        fun subtreeHeight(n: TreeNode): Float {
            val self = measure(n).height
            if (n.children.isEmpty()) return self
            var sum = 0f
            for ((idx, c) in n.children.withIndex()) {
                if (idx > 0) sum += rowGap
                sum += subtreeHeight(c)
            }
            return max(self, sum)
        }

        fun subtreeDepth(n: TreeNode): Int {
            if (n.children.isEmpty()) return 1
            return 1 + (n.children.maxOfOrNull(::subtreeDepth) ?: 0)
        }

        val requestedSides = parseRequestedSides(model)
        val sideByNode = LinkedHashMap<NodeId, Int>()
        sideByNode[model.root.id] = 0
        var leftLoad = 0f
        var rightLoad = 0f
        for (child in model.root.children) {
            val side = requestedSides[child.id] ?: if (leftLoad <= rightLoad) -1 else 1
            sideByNode[child.id] = side
            val load = subtreeHeight(child)
            if (side < 0) leftLoad += load + rowGap else rightLoad += load + rowGap
        }

        fun propagateSide(n: TreeNode, side: Int) {
            for (child in n.children) {
                val childSide = requestedSides[child.id] ?: side
                sideByNode[child.id] = childSide
                propagateSide(child, childSide)
            }
        }
        for (child in model.root.children) {
            val side = sideByNode[child.id] ?: 1
            propagateSide(child, side)
        }

        val leftDepth = model.root.children
            .filter { sideByNode[it.id] == -1 }
            .maxOfOrNull(::subtreeDepth)
            ?: 0
        val rightDepth = model.root.children
            .filter { sideByNode[it.id] == 1 }
            .maxOfOrNull(::subtreeDepth)
            ?: 0
        val rootLeft = pad + leftDepth * (180f + colGap)

        fun place(n: TreeNode, depth: Int, top: Float) {
            val size = measure(n)
            val subH = subtreeHeight(n)
            val side = sideByNode[n.id] ?: 0
            val x = when {
                side < 0 -> rootLeft - depth * (180f + colGap)
                side > 0 -> rootLeft + depth * (180f + colGap)
                else -> rootLeft
            }
            val y = top + (subH - size.height) / 2f
            val fresh = Rect(Point(x, y), size)
            nodePositions[n.id] = if (options.incremental) (prevPos[n.id] ?: fresh) else fresh

            if (n == model.root) {
                val leftChildren = n.children.filter { sideByNode[it.id] == -1 }
                val rightChildren = n.children.filter { sideByNode[it.id] != -1 }
                val leftHeight = leftChildren.sumOf { subtreeHeight(it).toDouble() }.toFloat() +
                    rowGap * (leftChildren.size - 1).coerceAtLeast(0)
                val rightHeight = rightChildren.sumOf { subtreeHeight(it).toDouble() }.toFloat() +
                    rowGap * (rightChildren.size - 1).coerceAtLeast(0)
                var leftTop = top + (subH - leftHeight) / 2f
                var rightTop = top + (subH - rightHeight) / 2f
                for (c in leftChildren) {
                    val ch = subtreeHeight(c)
                    place(c, depth + 1, leftTop)
                    leftTop += ch + rowGap
                }
                for (c in rightChildren) {
                    val ch = subtreeHeight(c)
                    place(c, depth + 1, rightTop)
                    rightTop += ch + rowGap
                }
            } else {
                var childTop = top
                for (c in n.children) {
                    val ch = subtreeHeight(c)
                    place(c, depth + 1, childTop)
                    childTop += ch + rowGap
                }
            }
        }

        place(model.root, depth = 0, top = pad)

        val maxRight = nodePositions.values.maxOfOrNull { it.right } ?: (pad + 56f)
        val minLeft = nodePositions.values.minOfOrNull { it.left } ?: 0f
        val maxBottom = nodePositions.values.maxOfOrNull { it.bottom } ?: (pad + 34f)
        return LaidOutDiagram(
            source = model,
            nodePositions = nodePositions,
            edgeRoutes = emptyList<EdgeRoute>(),
            clusterRects = emptyMap(),
            bounds = Rect.ltrb(minLeft.coerceAtMost(0f), 0f, maxRight + pad, maxBottom + pad),
        )
    }

    private fun parseRequestedSides(model: TreeIR): Map<NodeId, Int> {
        val raw = model.styleHints.extras[MINDMAP_SIDE_KEY]
            ?: model.styleHints.extras[WBS_SIDE_KEY]
            ?: ""
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<NodeId, Int>()
        for (entry in raw.split("||")) {
            if (entry.isEmpty()) continue
            val split = entry.lastIndexOf('|')
            if (split <= 0) continue
            val id = entry.substring(0, split)
            when (entry.substring(split + 1)) {
                "left" -> out[NodeId(id)] = -1
                "right" -> out[NodeId(id)] = 1
                "root" -> out[NodeId(id)] = 0
            }
        }
        return out
    }

    private fun parseNodeFloatMap(raw: String): Map<NodeId, Float> =
        raw.split("||").mapNotNull { entry ->
            if (entry.isEmpty()) return@mapNotNull null
            val split = entry.lastIndexOf('|')
            if (split <= 0) return@mapNotNull null
            val value = entry.substring(split + 1).trim().toFloatOrNull() ?: return@mapNotNull null
            NodeId(entry.substring(0, split)) to value
        }.toMap()
}
