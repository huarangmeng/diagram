package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.TreeNode
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for the initial PlantUML `wbs` slice.
 *
 * Supported subset:
 * - org-mode hierarchy: `*`, `**`, `***`
 * - arithmetic hierarchy: `+`, `++-`, `----`
 * - direction override markers: `<` / `>`
 * - boxless marker: `_`
 * - multiline labels using `: ... ;`
 */
@DiagramApi
class PlantUmlWbsParser {
    companion object {
        const val SIDE_KEY = "plantuml.wbs.side"
        const val BOXLESS_KEY = "plantuml.wbs.boxless"
        const val INLINE_COLOR_KEY = "plantuml.wbs.inlineColor"
        const val STEREOTYPE_KEY = "plantuml.wbs.stereotype"
        const val LEADING_VISUAL_KEY = "plantuml.wbs.leadingVisual"
        const val STYLE_COLOR_KEY = "plantuml.wbs.styleColor"
        const val STYLE_LINE_COLOR_KEY = "plantuml.wbs.styleLineColor"
        const val STYLE_FONT_COLOR_KEY = "plantuml.wbs.styleFontColor"
        const val STYLE_ROUND_CORNER_KEY = "plantuml.wbs.styleRoundCorner"

        private val PREFIX = Regex("""^([*+\-]+)([<>]?)(_?)\s*(.*)$""")
    }

    private data class MutableWbsNode(
        val id: NodeId,
        val label: String,
        val children: MutableList<MutableWbsNode> = ArrayList(),
    )

    private data class PendingMultiline(
        val depth: Int,
        val explicitSide: String?,
        val boxless: Boolean,
        val inlineColor: String?,
        val stereotype: String?,
        val leadingVisual: PlantUmlTreeLeadingVisual?,
        val nodeStyleColor: String?,
        val branchStyleColor: String?,
        val nodeStyleLineColor: String?,
        val branchStyleLineColor: String?,
        val nodeStyleFontColor: String?,
        val branchStyleFontColor: String?,
        val nodeStyleRoundCorner: String?,
        val branchStyleRoundCorner: String?,
        val lines: MutableList<String> = ArrayList(),
    )

    private data class ParsedDecorations(
        val cleanedText: String,
        val inlineColor: String?,
        val visibleStereotype: String?,
        val leadingVisual: PlantUmlTreeLeadingVisual?,
        val nodeStyleColor: String?,
        val branchStyleColor: String?,
        val nodeStyleLineColor: String?,
        val branchStyleLineColor: String?,
        val nodeStyleFontColor: String?,
        val branchStyleFontColor: String?,
        val nodeStyleRoundCorner: String?,
        val branchStyleRoundCorner: String?,
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0
    private var autoId = 0
    private var root: MutableWbsNode? = null
    private val stack: MutableList<Pair<Int, MutableWbsNode>> = ArrayList()
    private val sideByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val boxlessNodeIds: LinkedHashSet<NodeId> = LinkedHashSet()
    private val inlineColorByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val stereotypeByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val leadingVisualByNode: LinkedHashMap<NodeId, PlantUmlTreeLeadingVisual> = LinkedHashMap()
    private val styleColorByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val branchStyleColorByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val styleLineColorByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val branchStyleLineColorByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val styleFontColorByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val branchStyleFontColorByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val styleRoundCornerByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val branchStyleRoundCornerByNode: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val styleSupport = PlantUmlTreeStyleSupport("wbsDiagram")
    private var pendingMultiline: PendingMultiline? = null

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }
        if (styleSupport.acceptLine(trimmed)) return IrPatchBatch(seq, emptyList())

        pendingMultiline?.let { pending ->
            if (trimmed == ";" || trimmed.contains(";")) {
                val beforeTerminator = if (trimmed == ";") "" else trimmed.substringBefore(";").trimEnd()
                if (beforeTerminator.isNotEmpty()) pending.lines += beforeTerminator
                val suffixDecorations = if (trimmed == ";") null else parseDecorations(trimmed.substringAfter(";", "").trim())
                pendingMultiline = null
                return finalizePending(
                    pending = pending,
                    inlineColor = pending.inlineColor ?: suffixDecorations?.inlineColor,
                    stereotype = pending.stereotype ?: suffixDecorations?.visibleStereotype,
                    leadingVisual = pending.leadingVisual ?: suffixDecorations?.leadingVisual,
                    nodeStyleColor = pending.nodeStyleColor ?: suffixDecorations?.nodeStyleColor,
                    branchStyleColor = pending.branchStyleColor ?: suffixDecorations?.branchStyleColor,
                    nodeStyleLineColor = pending.nodeStyleLineColor ?: suffixDecorations?.nodeStyleLineColor,
                    branchStyleLineColor = pending.branchStyleLineColor ?: suffixDecorations?.branchStyleLineColor,
                    nodeStyleFontColor = pending.nodeStyleFontColor ?: suffixDecorations?.nodeStyleFontColor,
                    branchStyleFontColor = pending.branchStyleFontColor ?: suffixDecorations?.branchStyleFontColor,
                    nodeStyleRoundCorner = pending.nodeStyleRoundCorner ?: suffixDecorations?.nodeStyleRoundCorner,
                    branchStyleRoundCorner = pending.branchStyleRoundCorner ?: suffixDecorations?.branchStyleRoundCorner,
                )
            }
            pending.lines += trimmed
            return IrPatchBatch(seq, emptyList())
        }

        val parsed = PREFIX.matchEntire(trimmed)
            ?: return errorBatch("Invalid PlantUML wbs node line: $trimmed")
        val marker = parsed.groupValues[1]
        val direction = parsed.groupValues[2]
        val boxless = parsed.groupValues[3].isNotEmpty()
        val rest = parsed.groupValues[4]

        if (marker.any { it == '*' } && marker.any { it == '+' || it == '-' }) {
            return errorBatch("Mixed PlantUML wbs prefix families are not supported: $marker")
        }
        if (marker.first() == '*' && !marker.all { it == '*' }) {
            return errorBatch("Invalid PlantUML wbs org-mode prefix: $marker")
        }

        val depth = marker.length
        val explicitSide = when {
            direction == "<" -> "left"
            direction == ">" -> "right"
            marker.first() == '+' || marker.first() == '-' -> if (marker.last() == '-') "left" else "right"
            else -> null
        }
        val decorations = parseDecorations(rest)
        val inlineColor = decorations.inlineColor
        val stereotype = decorations.visibleStereotype
        val normalizedRest = decorations.cleanedText
        val leadingVisual = decorations.leadingVisual
        if (normalizedRest.startsWith(":")) {
            val firstLine = normalizedRest.removePrefix(":").removeSuffix(";").trim()
            if (normalizedRest.endsWith(";")) {
                val nodeId = NodeId(nextAutoId(firstLine.ifBlank { "wbs" }))
                val node = MutableWbsNode(
                    id = nodeId,
                    label = PlantUmlTreeDecorationSupport.displayLabel(
                        label = normalizeLabel(firstLine),
                        stereotype = stereotype,
                        leadingVisual = leadingVisual,
                    ),
                )
                inlineColor?.let { inlineColorByNode[nodeId] = it }
                stereotype?.let { stereotypeByNode[nodeId] = it }
                leadingVisual?.let { leadingVisualByNode[nodeId] = it }
                return attachNode(
                    depth = depth,
                    explicitSide = explicitSide,
                    boxless = boxless,
                    node = node,
                    nodeStyleColor = decorations.nodeStyleColor,
                    branchStyleColor = decorations.branchStyleColor,
                    nodeStyleLineColor = decorations.nodeStyleLineColor,
                    branchStyleLineColor = decorations.branchStyleLineColor,
                    nodeStyleFontColor = decorations.nodeStyleFontColor,
                    branchStyleFontColor = decorations.branchStyleFontColor,
                    nodeStyleRoundCorner = decorations.nodeStyleRoundCorner,
                    branchStyleRoundCorner = decorations.branchStyleRoundCorner,
                )
            }
            pendingMultiline = PendingMultiline(
                depth = depth,
                explicitSide = explicitSide,
                boxless = boxless,
                inlineColor = inlineColor,
                stereotype = stereotype,
                leadingVisual = leadingVisual,
                nodeStyleColor = decorations.nodeStyleColor,
                branchStyleColor = decorations.branchStyleColor,
                nodeStyleLineColor = decorations.nodeStyleLineColor,
                branchStyleLineColor = decorations.branchStyleLineColor,
                nodeStyleFontColor = decorations.nodeStyleFontColor,
                branchStyleFontColor = decorations.branchStyleFontColor,
                nodeStyleRoundCorner = decorations.nodeStyleRoundCorner,
                branchStyleRoundCorner = decorations.branchStyleRoundCorner,
            )
            if (firstLine.isNotEmpty()) pendingMultiline!!.lines += firstLine
            return IrPatchBatch(seq, emptyList())
        }

        val label = normalizeLabel(normalizedRest)
        if (label.isEmpty()) return errorBatch("Empty PlantUML wbs node label")
        val nodeId = NodeId(nextAutoId(label))
        return attachNode(
            depth = depth,
            explicitSide = explicitSide,
            boxless = boxless,
            node = MutableWbsNode(
                id = nodeId,
                label = PlantUmlTreeDecorationSupport.displayLabel(label, stereotype, leadingVisual),
            ),
            nodeStyleColor = decorations.nodeStyleColor,
            branchStyleColor = decorations.branchStyleColor,
            nodeStyleLineColor = decorations.nodeStyleLineColor,
            branchStyleLineColor = decorations.branchStyleLineColor,
            nodeStyleFontColor = decorations.nodeStyleFontColor,
            branchStyleFontColor = decorations.branchStyleFontColor,
            nodeStyleRoundCorner = decorations.nodeStyleRoundCorner,
            branchStyleRoundCorner = decorations.branchStyleRoundCorner,
        ).also {
            inlineColor?.let { color -> inlineColorByNode[nodeId] = color }
            stereotype?.let { value -> stereotypeByNode[nodeId] = value }
            leadingVisual?.let { value -> leadingVisualByNode[nodeId] = value }
        }
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (pendingMultiline != null) {
            val d = Diagnostic(
                severity = Severity.ERROR,
                message = "Unclosed PlantUML wbs multiline node before end of block",
                code = "PLANTUML-E012",
            )
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
            pendingMultiline = null
        }
        if (!blockClosed) {
            val d = Diagnostic(
                severity = Severity.ERROR,
                message = "Missing @endwbs closing delimiter",
                code = "PLANTUML-E012",
            )
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
        }
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): TreeIR {
        val actualRoot = root ?: MutableWbsNode(NodeId("wbs_root"), "wbs")
        val extras = LinkedHashMap<String, String>()
        if (sideByNode.isNotEmpty()) {
            extras[SIDE_KEY] = sideByNode.entries.joinToString("||") { (id, side) -> "${id.value}|$side" }
        }
        if (boxlessNodeIds.isNotEmpty()) {
            extras[BOXLESS_KEY] = boxlessNodeIds.joinToString("||") { it.value }
        }
        if (inlineColorByNode.isNotEmpty()) {
            extras[INLINE_COLOR_KEY] = inlineColorByNode.entries.joinToString("||") { (id, color) -> "${id.value}|$color" }
        }
        if (stereotypeByNode.isNotEmpty()) {
            extras[STEREOTYPE_KEY] = stereotypeByNode.entries.joinToString("||") { (id, stereotype) -> "${id.value}|$stereotype" }
        }
        if (leadingVisualByNode.isNotEmpty()) {
            extras[LEADING_VISUAL_KEY] =
                leadingVisualByNode.entries.joinToString("||") { (id, leadingVisual) ->
                    "${id.value}|${PlantUmlTreeDecorationSupport.encodeLeadingVisual(leadingVisual)}"
                }
        }
        if (styleColorByNode.isNotEmpty()) {
            extras[STYLE_COLOR_KEY] = styleColorByNode.entries.joinToString("||") { (id, color) -> "${id.value}|$color" }
        }
        if (styleLineColorByNode.isNotEmpty()) {
            extras[STYLE_LINE_COLOR_KEY] = styleLineColorByNode.entries.joinToString("||") { (id, color) -> "${id.value}|$color" }
        }
        if (styleFontColorByNode.isNotEmpty()) {
            extras[STYLE_FONT_COLOR_KEY] = styleFontColorByNode.entries.joinToString("||") { (id, color) -> "${id.value}|$color" }
        }
        if (styleRoundCornerByNode.isNotEmpty()) {
            extras[STYLE_ROUND_CORNER_KEY] = styleRoundCornerByNode.entries.joinToString("||") { (id, value) -> "${id.value}|$value" }
        }
        return TreeIR(
            root = freeze(actualRoot),
            title = null,
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(extras = extras),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun attachNode(
        depth: Int,
        explicitSide: String?,
        boxless: Boolean,
        node: MutableWbsNode,
        nodeStyleColor: String?,
        branchStyleColor: String?,
        nodeStyleLineColor: String?,
        branchStyleLineColor: String?,
        nodeStyleFontColor: String?,
        branchStyleFontColor: String?,
        nodeStyleRoundCorner: String?,
        branchStyleRoundCorner: String?,
    ): IrPatchBatch {
        if (root == null) {
            root = node
            sideByNode[node.id] = "root"
            if (boxless) boxlessNodeIds += node.id
            nodeStyleColor?.let { styleColorByNode[node.id] = it }
            branchStyleColor?.let { branchStyleColorByNode[node.id] = it }
            nodeStyleLineColor?.let { styleLineColorByNode[node.id] = it }
            branchStyleLineColor?.let { branchStyleLineColorByNode[node.id] = it }
            nodeStyleFontColor?.let { styleFontColorByNode[node.id] = it }
            branchStyleFontColor?.let { branchStyleFontColorByNode[node.id] = it }
            nodeStyleRoundCorner?.let { styleRoundCornerByNode[node.id] = it }
            branchStyleRoundCorner?.let { branchStyleRoundCornerByNode[node.id] = it }
            stack.clear()
            stack += depth to node
            return IrPatchBatch(seq, emptyList())
        }
        while (stack.isNotEmpty() && depth <= stack.last().first) stack.removeAt(stack.lastIndex)
        val parent = stack.lastOrNull()?.second
            ?: return errorBatch("PlantUML wbs does not yet support multiple roots")
        parent.children += node
        stack += depth to node
        sideByNode[node.id] = explicitSide ?: sideByNode[parent.id].orEmpty().ifEmpty { "auto" }
        val inheritedBranchStyle = branchStyleColorByNode[parent.id]
        val effectiveStyleColor = nodeStyleColor ?: inheritedBranchStyle
        effectiveStyleColor?.let { styleColorByNode[node.id] = it }
        (branchStyleColor ?: inheritedBranchStyle)?.let { branchStyleColorByNode[node.id] = it }
        val inheritedBranchLineColor = branchStyleLineColorByNode[parent.id]
        val effectiveLineColor = nodeStyleLineColor ?: inheritedBranchLineColor
        effectiveLineColor?.let { styleLineColorByNode[node.id] = it }
        (branchStyleLineColor ?: inheritedBranchLineColor)?.let { branchStyleLineColorByNode[node.id] = it }
        val inheritedBranchFontColor = branchStyleFontColorByNode[parent.id]
        val effectiveFontColor = nodeStyleFontColor ?: inheritedBranchFontColor
        effectiveFontColor?.let { styleFontColorByNode[node.id] = it }
        (branchStyleFontColor ?: inheritedBranchFontColor)?.let { branchStyleFontColorByNode[node.id] = it }
        val inheritedBranchRoundCorner = branchStyleRoundCornerByNode[parent.id]
        val effectiveRoundCorner = nodeStyleRoundCorner ?: inheritedBranchRoundCorner
        effectiveRoundCorner?.let { styleRoundCornerByNode[node.id] = it }
        (branchStyleRoundCorner ?: inheritedBranchRoundCorner)?.let { branchStyleRoundCornerByNode[node.id] = it }
        if (boxless) boxlessNodeIds += node.id
        return IrPatchBatch(seq, emptyList())
    }

    private fun finalizePending(
        pending: PendingMultiline,
        inlineColor: String?,
        stereotype: String?,
        leadingVisual: PlantUmlTreeLeadingVisual?,
        nodeStyleColor: String?,
        branchStyleColor: String?,
        nodeStyleLineColor: String?,
        branchStyleLineColor: String?,
        nodeStyleFontColor: String?,
        branchStyleFontColor: String?,
        nodeStyleRoundCorner: String?,
        branchStyleRoundCorner: String?,
    ): IrPatchBatch {
        val node = MutableWbsNode(
            id = NodeId(nextAutoId("wbs")),
            label = PlantUmlTreeDecorationSupport.displayLabel(
                label = normalizeLabel(pending.lines.joinToString("\n").trim()),
                stereotype = stereotype,
                leadingVisual = leadingVisual,
            ),
        )
        inlineColor?.let { inlineColorByNode[node.id] = it }
        stereotype?.let { stereotypeByNode[node.id] = it }
        leadingVisual?.let { leadingVisualByNode[node.id] = it }
        return attachNode(
            depth = pending.depth,
            explicitSide = pending.explicitSide,
            boxless = pending.boxless,
            node = node,
            nodeStyleColor = nodeStyleColor,
            branchStyleColor = branchStyleColor,
            nodeStyleLineColor = nodeStyleLineColor,
            branchStyleLineColor = branchStyleLineColor,
            nodeStyleFontColor = nodeStyleFontColor,
            branchStyleFontColor = branchStyleFontColor,
            nodeStyleRoundCorner = nodeStyleRoundCorner,
            branchStyleRoundCorner = branchStyleRoundCorner,
        )
    }

    private fun parseDecorations(raw: String): ParsedDecorations {
        val decorations = PlantUmlTreeDecorationSupport.parse(raw)
        val resolvedStyle = styleSupport.resolve(decorations.stereotype)
        val visibleStereotype = if (resolvedStyle != null) null else decorations.stereotype
        val (cleanedText, leadingVisual) = PlantUmlTreeDecorationSupport.parseLeadingVisual(decorations.cleanedText)
        return ParsedDecorations(
            cleanedText = cleanedText,
            inlineColor = decorations.inlineColor,
            visibleStereotype = visibleStereotype,
            leadingVisual = leadingVisual,
            nodeStyleColor = resolvedStyle?.nodeBackground,
            branchStyleColor = resolvedStyle?.branchBackground,
            nodeStyleLineColor = resolvedStyle?.nodeLineColor,
            branchStyleLineColor = resolvedStyle?.branchLineColor,
            nodeStyleFontColor = resolvedStyle?.nodeFontColor,
            branchStyleFontColor = resolvedStyle?.branchFontColor,
            nodeStyleRoundCorner = resolvedStyle?.nodeRoundCorner,
            branchStyleRoundCorner = resolvedStyle?.branchRoundCorner,
        )
    }

    private fun freeze(node: MutableWbsNode): TreeNode =
        TreeNode(
            id = node.id,
            label = RichLabel.Plain(node.label),
            children = node.children.map(::freeze),
        )

    private fun normalizeLabel(raw: String): String =
        raw.trim()
            .replace("<br>", "\n", ignoreCase = true)
            .replace("\t", "    ")

    private fun nextAutoId(seed: String): String {
        autoId++
        val base = seed.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (base.isBlank()) "wbs_$autoId" else "${base}_$autoId"
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "PLANTUML-E012")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
