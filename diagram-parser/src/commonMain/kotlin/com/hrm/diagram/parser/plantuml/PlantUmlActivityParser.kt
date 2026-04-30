package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.ActivityBlock
import com.hrm.diagram.core.ir.ActivityIR
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for the Phase-4 PlantUML `activity` MVP.
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlActivityParser()
 * parser.acceptLine("start")
 * parser.acceptLine(":Load data;")
 * parser.acceptLine("stop")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlActivityParser {
    companion object {
        const val HAS_START_KEY = "plantuml.activity.hasStart"
        const val HAS_STOP_KEY = "plantuml.activity.hasStop"
        const val REPEAT_PREFIX = "__plantuml_repeat__::"
        const val SWIMLANE_PREFIX = "__plantuml_swimlane__::"
        const val ACTION_STYLE_PREFIX = "__plantuml_action_style__::"
        const val EDGE_LABEL_PREFIX = "__plantuml_edge_label__::"
        const val SYNC_BAR_PREFIX = "__plantuml_sync_bar__::"
        const val NODE_REF_PREFIX = "__plantuml_node_ref__::"
        const val EDGE_SOURCE_PREFIX = "__plantuml_edge_source__::"
        const val EDGE_TARGET_PREFIX = "__plantuml_edge_target__::"
        const val EDGE_STOP_PREFIX = "__plantuml_edge_stop__::"
        const val STYLE_ACTION_FILL_KEY = "plantuml.activity.style.action.fill"
        const val STYLE_ACTION_STROKE_KEY = "plantuml.activity.style.action.stroke"
        const val STYLE_ACTION_TEXT_KEY = "plantuml.activity.style.action.text"
        const val STYLE_DECISION_FILL_KEY = "plantuml.activity.style.decision.fill"
        const val STYLE_DECISION_STROKE_KEY = "plantuml.activity.style.decision.stroke"
        const val STYLE_DECISION_TEXT_KEY = "plantuml.activity.style.decision.text"
        const val STYLE_NOTE_FILL_KEY = "plantuml.activity.style.note.fill"
        const val STYLE_NOTE_STROKE_KEY = "plantuml.activity.style.note.stroke"
        const val STYLE_NOTE_TEXT_KEY = "plantuml.activity.style.note.text"
        const val STYLE_BAR_FILL_KEY = "plantuml.activity.style.bar.fill"
        const val STYLE_BAR_TEXT_KEY = "plantuml.activity.style.bar.text"
        const val STYLE_START_FILL_KEY = "plantuml.activity.style.start.fill"
        const val STYLE_STOP_STROKE_KEY = "plantuml.activity.style.stop.stroke"
        const val STYLE_EDGE_COLOR_KEY = "plantuml.activity.style.edge.color"

        private const val LANE_STYLE_SEPARATOR = "|||"
    }

    private sealed interface Frame {
        val target: MutableList<ActivityBlock>

        data class Root(override val target: MutableList<ActivityBlock>) : Frame

        data class IfFrame(
            val branches: MutableList<IfBranch>,
            var activeIndex: Int = 0,
            val incomingSourceRef: String? = null,
            val incomingEdgeLabel: String? = null,
        ) : Frame {
            override val target: MutableList<ActivityBlock> get() = branches[activeIndex].blocks
        }

        data class WhileFrame(
            val cond: RichLabel,
            val body: MutableList<ActivityBlock> = mutableListOf(),
        ) : Frame {
            override val target: MutableList<ActivityBlock> get() = body
        }

        data class RepeatFrame(
            val body: MutableList<ActivityBlock> = mutableListOf(),
        ) : Frame {
            override val target: MutableList<ActivityBlock> get() = body
        }

        data class ForkFrame(
            val branches: MutableList<MutableList<ActivityBlock>> = mutableListOf(mutableListOf()),
            var activeIndex: Int = 0,
        ) : Frame {
            override val target: MutableList<ActivityBlock> get() = branches[activeIndex]
        }
    }

    private data class IfBranch(
        val cond: RichLabel?,
        val blocks: MutableList<ActivityBlock> = mutableListOf(),
    )

    private data class PendingNote(
        val placement: String,
        val lines: MutableList<String> = mutableListOf(),
    )

    private data class PartitionFrame(
        val name: String,
        val color: String?,
    )

    private data class LegacyTarget(
        val kind: Kind,
        val label: String? = null,
        val refKey: String? = null,
        val aliasKey: String? = null,
        val color: String? = null,
    ) {
        enum class Kind { Start, If, ExistingRef, Action, SyncBar }
    }

    private val rootBlocks: MutableList<ActivityBlock> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val frames: ArrayDeque<Frame> = ArrayDeque<Frame>().apply { addLast(Frame.Root(rootBlocks)) }
    private val partitionStack: ArrayDeque<PartitionFrame> = ArrayDeque()
    private val knownRefs: MutableSet<String> = LinkedHashSet()

    private var seq: Long = 0
    private var hasStart: Boolean = false
    private var hasStop: Boolean = false
    private var currentLane: String? = null
    private var pendingNote: PendingNote? = null
    private var pendingSkinparamBlock: Boolean = false

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }

        pendingNote?.let { note ->
            if (trimmed.equals("end note", ignoreCase = true) || trimmed.equals("endnote", ignoreCase = true)) {
                pendingNote = null
                return addBlock(ActivityBlock.Note(RichLabel.Plain(note.lines.joinToString("\n").trim())))
            }
            note.lines += trimmed
            return IrPatchBatch(seq, emptyList())
        }
        if (pendingSkinparamBlock) {
            if (trimmed == "}") {
                pendingSkinparamBlock = false
                return IrPatchBatch(seq, emptyList())
            }
            return applySkinparamEntry(trimmed)
        }

        return when {
            trimmed.startsWith("skinparam", ignoreCase = true) -> applySkinparam(trimmed)
            trimmed.equals("start", ignoreCase = true) -> {
                hasStart = true
                IrPatchBatch(seq, emptyList())
            }
            trimmed.equals("stop", ignoreCase = true) || trimmed.equals("end", ignoreCase = true) -> {
                hasStop = true
                IrPatchBatch(seq, emptyList())
            }
            trimmed == "}" -> closePartition()
            trimmed.startsWith("partition ", ignoreCase = true) -> openPartition(trimmed)
            isSwimlane(trimmed) -> switchSwimlane(trimmed)
            isSyncBar(trimmed) -> addSyncBar(trimmed)
            trimmed.startsWith("#") && ':' in trimmed -> addStyledAction(trimmed)
            trimmed.startsWith(":") && trimmed.endsWith(";") -> addBlock(ActivityBlock.Action(RichLabel.Plain(trimmed.removePrefix(":").removeSuffix(";").trim())))
            trimmed.startsWith("if(", ignoreCase = true) ||
                (trimmed.startsWith("if ", ignoreCase = true) && extractParenCondition(trimmed.removePrefix("if").trim()) != null) -> openIf(trimmed)
            isLegacyIf(trimmed) -> openLegacyIf(trimmed)
            trimmed.startsWith("elseif ", ignoreCase = true) || trimmed.startsWith("elseif(", ignoreCase = true) -> switchElseIf(trimmed)
            trimmed.startsWith("else", ignoreCase = true) -> switchElse()
            trimmed.equals("endif", ignoreCase = true) -> closeIf()
            trimmed.startsWith("while ", ignoreCase = true) || trimmed.startsWith("while(", ignoreCase = true) -> openWhile(trimmed)
            trimmed.equals("endwhile", ignoreCase = true) -> closeWhile()
            trimmed.equals("repeat", ignoreCase = true) -> openRepeat()
            trimmed.startsWith("repeat while", ignoreCase = true) -> closeRepeat(trimmed)
            trimmed.equals("fork", ignoreCase = true) -> openFork()
            trimmed.equals("fork again", ignoreCase = true) -> nextForkBranch()
            trimmed.equals("end fork", ignoreCase = true) -> closeFork()
            trimmed.startsWith("note ", ignoreCase = true) || trimmed.startsWith("note:", ignoreCase = true) -> addNote(trimmed)
            isLegacyArrow(trimmed) -> parseLegacyArrow(trimmed)
            else -> errorBatch("Unsupported PlantUML activity statement: $trimmed")
        }
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        while (frames.size > 1) {
            val frame = frames.removeLast()
            val message = when (frame) {
                is Frame.IfFrame -> "Unclosed if block before end of PlantUML block"
                is Frame.WhileFrame -> "Unclosed while block before end of PlantUML block"
                is Frame.RepeatFrame -> "Unclosed repeat block before end of PlantUML block"
                is Frame.ForkFrame -> "Unclosed fork block before end of PlantUML block"
                is Frame.Root -> "Unexpected parser root state"
            }
            out += addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E007"))
        }
        if (!blockClosed) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Missing '@enduml' terminator",
                    code = "PLANTUML-E001",
                ),
            )
        }
        if (pendingNote != null) {
            out += addDiagnostic(Diagnostic(Severity.ERROR, "Unclosed note block before end of PlantUML block", "PLANTUML-E007"))
            pendingNote = null
        }
        if (partitionStack.isNotEmpty()) {
            out += addDiagnostic(Diagnostic(Severity.ERROR, "Unclosed partition block before end of PlantUML block", "PLANTUML-E007"))
            partitionStack.clear()
            currentLane = null
        }
        if (pendingSkinparamBlock) {
            out += addDiagnostic(Diagnostic(Severity.WARNING, "Unsupported or unclosed 'skinparam activity' block ignored", "PLANTUML-W001"))
            pendingSkinparamBlock = false
        }
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): ActivityIR = ActivityIR(
        blocks = rootBlocks.toList(),
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(
            extras = buildMap {
                put(HAS_START_KEY, hasStart.toString())
                put(HAS_STOP_KEY, hasStop.toString())
                putAll(styleExtras)
            },
        ),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun currentTarget(): MutableList<ActivityBlock> = frames.last().target
    private val styleExtras: LinkedHashMap<String, String> = LinkedHashMap()

    private fun addBlock(block: ActivityBlock): IrPatchBatch {
        currentLane?.let { lane ->
            val target = currentTarget()
            val marker = laneMarker(lane)
            if (lastLaneMarker(target) != marker) {
                target += marker
            }
        }
        autoRegisterRefs(block)
        currentTarget() += block
        return IrPatchBatch(seq, emptyList())
    }

    private fun addNote(line: String): IrPatchBatch {
        parseSingleLineNote(line)?.let { text ->
            return addBlock(ActivityBlock.Note(RichLabel.Plain(text)))
        }
        parseMultilineNotePlacement(line)?.let { placement ->
            pendingNote = PendingNote(placement = placement)
            return IrPatchBatch(seq, emptyList())
        }
        val text = when {
            line.startsWith("note:", ignoreCase = true) -> line.substringAfter(':').trim()
            ':' in line -> line.substringAfter(':').trim()
            else -> line.removePrefix("note").trim()
        }
        return addBlock(ActivityBlock.Note(RichLabel.Plain(text)))
    }

    private fun addStyledAction(line: String): IrPatchBatch {
        val color = line.substringBefore(':').trim()
        val label = line.substringAfter(':').removeSuffix(";").trim().removeSurrounding("\"")
        if (label.isEmpty()) return errorBatch("Invalid PlantUML activity styled action syntax: $line")
        currentTarget() += ActivityBlock.Note(RichLabel.Plain(ACTION_STYLE_PREFIX + color))
        return addBlock(ActivityBlock.Action(RichLabel.Plain(label)))
    }

    private fun addSyncBar(line: String): IrPatchBatch {
        val label = parseSyncBarLabel(line) ?: return errorBatch("Invalid PlantUML activity sync bar syntax: $line")
        registerRef(syncRefKey(label))
        currentTarget() += ActivityBlock.Note(RichLabel.Plain(NODE_REF_PREFIX + syncRefKey(label)))
        return addBlock(ActivityBlock.Note(RichLabel.Plain(SYNC_BAR_PREFIX + label)))
    }

    private fun openIf(line: String): IrPatchBatch {
        val cond = extractParenCondition(line.removePrefix("if").trim())
            ?: return errorBatch("Invalid PlantUML activity if syntax: $line")
        frames.addLast(Frame.IfFrame(branches = mutableListOf(IfBranch(cond = RichLabel.Plain(cond)))))
        return IrPatchBatch(seq, emptyList())
    }

    private fun openLegacyIf(line: String, sourceRef: String? = null, edgeLabel: String? = null): IrPatchBatch {
        val cond = parseLegacyIfCondition(line) ?: return errorBatch("Invalid PlantUML activity if syntax: $line")
        frames.addLast(
            Frame.IfFrame(
                branches = mutableListOf(IfBranch(cond = RichLabel.Plain(cond))),
                incomingSourceRef = sourceRef,
                incomingEdgeLabel = edgeLabel,
            ),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun switchElse(): IrPatchBatch {
        val frame = frames.lastOrNull() as? Frame.IfFrame ?: return errorBatch("'else' without matching 'if'")
        if (frame.branches.last().cond == null) return errorBatch("Duplicate 'else' in PlantUML activity if block")
        frame.branches += IfBranch(cond = null)
        frame.activeIndex = frame.branches.lastIndex
        return IrPatchBatch(seq, emptyList())
    }

    private fun switchElseIf(line: String): IrPatchBatch {
        val frame = frames.lastOrNull() as? Frame.IfFrame ?: return errorBatch("'elseif' without matching 'if'")
        if (frame.branches.last().cond == null) return errorBatch("'elseif' cannot appear after 'else'")
        val cond = extractParenCondition(line.removePrefix("elseif").trim())
            ?: return errorBatch("Invalid PlantUML activity elseif syntax: $line")
        frame.branches += IfBranch(cond = RichLabel.Plain(cond))
        frame.activeIndex = frame.branches.lastIndex
        return IrPatchBatch(seq, emptyList())
    }

    private fun closeIf(): IrPatchBatch {
        val frame = frames.removeLastOrNull() as? Frame.IfFrame ?: return errorBatch("'endif' without matching 'if'")
        frame.incomingSourceRef?.let { currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_SOURCE_PREFIX + it)) }
        frame.incomingEdgeLabel?.let { currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_LABEL_PREFIX + it)) }
        currentTarget() += buildIfElse(frame.branches)
        return IrPatchBatch(seq, emptyList())
    }

    private fun openWhile(line: String): IrPatchBatch {
        val cond = Regex("^while\\s*\\((.*?)\\)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.trim()
            ?: return errorBatch("Invalid PlantUML activity while syntax: $line")
        frames.addLast(Frame.WhileFrame(cond = RichLabel.Plain(cond)))
        return IrPatchBatch(seq, emptyList())
    }

    private fun closeWhile(): IrPatchBatch {
        val frame = frames.removeLastOrNull() as? Frame.WhileFrame ?: return errorBatch("'endwhile' without matching 'while'")
        currentTarget() += ActivityBlock.While(cond = frame.cond, body = frame.body.toList())
        return IrPatchBatch(seq, emptyList())
    }

    private fun openRepeat(): IrPatchBatch {
        frames.addLast(Frame.RepeatFrame())
        return IrPatchBatch(seq, emptyList())
    }

    private fun closeRepeat(line: String): IrPatchBatch {
        val frame = frames.removeLastOrNull() as? Frame.RepeatFrame ?: return errorBatch("'repeat while' without matching 'repeat'")
        val cond = extractParenCondition(line.removePrefix("repeat while").trim())
            ?: return errorBatch("Invalid PlantUML activity repeat syntax: $line")
        currentTarget() += ActivityBlock.While(cond = RichLabel.Plain(REPEAT_PREFIX + cond), body = frame.body.toList())
        return IrPatchBatch(seq, emptyList())
    }

    private fun openFork(): IrPatchBatch {
        frames.addLast(Frame.ForkFrame())
        return IrPatchBatch(seq, emptyList())
    }

    private fun nextForkBranch(): IrPatchBatch {
        val frame = frames.lastOrNull() as? Frame.ForkFrame ?: return errorBatch("'fork again' without matching 'fork'")
        frame.branches.add(mutableListOf())
        frame.activeIndex = frame.branches.lastIndex
        return IrPatchBatch(seq, emptyList())
    }

    private fun closeFork(): IrPatchBatch {
        val frame = frames.removeLastOrNull() as? Frame.ForkFrame ?: return errorBatch("'end fork' without matching 'fork'")
        currentTarget() += ActivityBlock.ForkJoin(branches = frame.branches.map { it.toList() })
        return IrPatchBatch(seq, emptyList())
    }

    private fun switchSwimlane(line: String): IrPatchBatch {
        currentLane = line.removePrefix("|").removeSuffix("|").trim().ifEmpty { null }
        return IrPatchBatch(seq, emptyList())
    }

    private fun openPartition(line: String): IrPatchBatch {
        val partition = parsePartitionDecl(line) ?: return errorBatch("Invalid PlantUML activity partition syntax: $line")
        val name = partition.first.removeSurrounding("\"").ifEmpty {
            return errorBatch("Invalid PlantUML activity partition syntax: $line")
        }
        val color = partition.second
        partitionStack.addLast(PartitionFrame(name = name, color = color))
        currentLane = name
        return IrPatchBatch(seq, emptyList())
    }

    private fun closePartition(): IrPatchBatch {
        if (partitionStack.isEmpty()) return errorBatch("'}' without matching 'partition'")
        partitionStack.removeLast()
        currentLane = partitionStack.lastOrNull()?.name
        return IrPatchBatch(seq, emptyList())
    }

    private fun isSwimlane(line: String): Boolean =
        line.startsWith("|") && line.endsWith("|") && line.length >= 2

    private fun isSyncBar(line: String): Boolean = parseSyncBarLabel(line) != null

    private fun parseLegacyArrow(line: String): IrPatchBatch {
        val parsed = parseLegacyArrowParts(line) ?: return errorBatch("Invalid PlantUML activity legacy arrow syntax: $line")
        val source = parsed.first
        val edgeLabel = parsed.second
        val target = parsed.third

        val sourceRef = source?.let(::toRefKey)
        if (source != null && isLegacyStart(source)) hasStart = true else sourceRef?.let {
            currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_SOURCE_PREFIX + it))
        }

        val targetSpec = parseLegacyTarget(target)
        return when (targetSpec.kind) {
            LegacyTarget.Kind.Start -> {
                edgeLabel?.let { currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_LABEL_PREFIX + it)) }
                sourceRef?.let { currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_STOP_PREFIX + it)) }
                hasStop = true
                IrPatchBatch(seq, emptyList())
            }
            LegacyTarget.Kind.If -> {
                openLegacyIf(target, sourceRef = sourceRef, edgeLabel = edgeLabel)
            }
            LegacyTarget.Kind.ExistingRef -> {
                edgeLabel?.let { currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_LABEL_PREFIX + it)) }
                currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_TARGET_PREFIX + targetSpec.refKey!!))
                IrPatchBatch(seq, emptyList())
            }
            LegacyTarget.Kind.Action -> {
                edgeLabel?.let { currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_LABEL_PREFIX + it)) }
                targetSpec.aliasKey?.let {
                    registerRef(it)
                    currentTarget() += ActivityBlock.Note(RichLabel.Plain(NODE_REF_PREFIX + it))
                }
                targetSpec.color?.let { currentTarget() += ActivityBlock.Note(RichLabel.Plain(ACTION_STYLE_PREFIX + it)) }
                addBlock(ActivityBlock.Action(RichLabel.Plain(targetSpec.label!!)))
            }
            LegacyTarget.Kind.SyncBar -> {
                edgeLabel?.let { currentTarget() += ActivityBlock.Note(RichLabel.Plain(EDGE_LABEL_PREFIX + it)) }
                val ref = targetSpec.refKey!!
                registerRef(ref)
                currentTarget() += ActivityBlock.Note(RichLabel.Plain(NODE_REF_PREFIX + ref))
                addBlock(ActivityBlock.Note(RichLabel.Plain(SYNC_BAR_PREFIX + targetSpec.label!!)))
            }
        }
    }

    private fun isLegacyStart(token: String): Boolean {
        val trimmed = token.trim()
        return trimmed == "(*)" || trimmed.equals("(*top)", ignoreCase = true)
    }

    private fun parseLegacyTargetLabel(token: String): String? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("\"")) {
            val closing = trimmed.indexOf('"', startIndex = 1)
            if (closing <= 0) return null
            return trimmed.substring(1, closing)
        }
        return when {
            trimmed.startsWith("if ", ignoreCase = true) -> trimmed
            trimmed.startsWith("if(", ignoreCase = true) -> trimmed
            else -> trimmed.removeSurrounding("\"").ifEmpty { null }
        }
    }

    private fun parseSingleLineNote(line: String): String? {
        val lower = line.lowercase()
        if (!lower.startsWith("note ")) return null
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val placement = line.substring(5, colon).trim().lowercase()
        if (placement !in setOf("left", "right", "top", "bottom")) return null
        return line.substring(colon + 1).trim()
    }

    private fun parseMultilineNotePlacement(line: String): String? {
        val lower = line.lowercase()
        if (!lower.startsWith("note ")) return null
        val placement = line.substring(5).trim().lowercase()
        return placement.takeIf { it in setOf("left", "right", "top", "bottom") }
    }

    private fun isLegacyIf(line: String): Boolean = parseLegacyIfCondition(line) != null

    private fun parseLegacyIfCondition(line: String): String? {
        if (!line.startsWith("if ", ignoreCase = true)) return null
        val body = line.substring(2).trim()
        val thenIndex = body.lowercase().indexOf(" then")
        if (thenIndex <= 0) return null
        val condPart = body.substring(0, thenIndex).trim()
        return when {
            condPart.startsWith("\"") && condPart.endsWith("\"") && condPart.length >= 2 -> condPart.substring(1, condPart.length - 1).trim()
            condPart.startsWith("(") && condPart.endsWith(")") && condPart.length >= 2 -> condPart.substring(1, condPart.length - 1).trim()
            else -> null
        }.takeIf { !it.isNullOrEmpty() }
    }

    private fun parsePartitionDecl(line: String): Pair<String, String?>? {
        if (!line.startsWith("partition ", ignoreCase = true)) return null
        if (!line.endsWith("{")) return null
        val body = line.substringAfter("partition", "").removeSuffix("{").trim()
        if (body.isEmpty()) return null
        val colorIndex = body.lastIndexOf(" #")
        return if (colorIndex >= 0) {
            body.substring(0, colorIndex).trim() to body.substring(colorIndex + 1).trim().ifEmpty { null }
        } else {
            body to null
        }
    }

    private fun isLegacyArrow(line: String): Boolean = parseLegacyArrowParts(line) != null

    private fun parseLegacyTarget(token: String): LegacyTarget {
        val trimmed = token.trim()
        if (isLegacyStart(trimmed)) return LegacyTarget(LegacyTarget.Kind.Start)
        if (isLegacyIf(trimmed)) return LegacyTarget(LegacyTarget.Kind.If)
        parseSyncBarLabel(trimmed)?.let { sync ->
            val ref = syncRefKey(sync)
            return if (knownRefs.contains(ref)) {
                LegacyTarget(LegacyTarget.Kind.ExistingRef, refKey = ref)
            } else {
                LegacyTarget(LegacyTarget.Kind.SyncBar, label = sync, refKey = ref)
            }
        }
        if (trimmed.startsWith("#") && ':' in trimmed) {
            val color = trimmed.substringBefore(':').trim()
            val nested = parseLegacyTarget(trimmed.substringAfter(':').trim())
            return if (nested.kind == LegacyTarget.Kind.Action) nested.copy(color = color) else nested
        }
        val aliasIndex = trimmed.lastIndexOf(" as ")
        if (aliasIndex > 0) {
            val labelPart = trimmed.substring(0, aliasIndex).trim()
            val alias = trimmed.substring(aliasIndex + 4).trim()
            val label = parseLegacyTargetLabel(labelPart)
            if (label != null && alias.isNotEmpty()) {
                return LegacyTarget(LegacyTarget.Kind.Action, label = label, aliasKey = nameRefKey(alias))
            }
        }
        val label = parseLegacyTargetLabel(trimmed)
        if (label != null) {
            val sourceWasQuoted = trimmed.startsWith("\"")
            if (!sourceWasQuoted && !trimmed.contains(' ') && knownRefs.contains(nameRefKey(trimmed))) {
                return LegacyTarget(LegacyTarget.Kind.ExistingRef, refKey = nameRefKey(trimmed))
            }
            return LegacyTarget(LegacyTarget.Kind.Action, label = label)
        }
        return LegacyTarget(LegacyTarget.Kind.ExistingRef, refKey = nameRefKey(trimmed))
    }

    private fun parseSyncBarLabel(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("===") || !trimmed.endsWith("===")) return null
        if (trimmed.length < 6) return null
        val inner = trimmed.removePrefix("===").removeSuffix("===").trim()
        return inner
    }

    private fun parseLegacyArrowParts(line: String): Triple<String?, String?, String>? {
        val arrowIndex = findLegacyArrowIndex(line) ?: return null
        val source = line.substring(0, arrowIndex).trim().ifEmpty { null }
        val arrowToken = readLegacyArrowToken(line, arrowIndex) ?: return null
        var cursor = arrowIndex + arrowToken.length
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
        var edgeLabel: String? = null
        if (cursor < line.length && line[cursor] == '[') {
            val end = line.indexOf(']', cursor + 1)
            if (end < 0) return null
            edgeLabel = line.substring(cursor + 1, end).trim().ifEmpty { null }
            cursor = end + 1
        }
        val target = line.substring(cursor).trim()
        if (target.isEmpty()) return null
        return Triple(source, edgeLabel, target)
    }

    private fun findLegacyArrowIndex(line: String): Int? {
        val candidates = listOf("-up->", "-down->", "-left->", "-right->", "-->", "->")
        return candidates.map { candidate -> line.indexOf(candidate) }.filter { it >= 0 }.minOrNull()
    }

    private fun readLegacyArrowToken(line: String, start: Int): String? {
        val candidates = listOf("-right->", "-left->", "-down->", "-up->", "-->", "->")
        return candidates.firstOrNull { line.startsWith(it, start) }
    }

    private fun toRefKey(token: String): String {
        val trimmed = token.trim()
        parseSyncBarLabel(trimmed)?.let { return syncRefKey(it) }
        if (trimmed.startsWith("\"")) return labelRefKey(parseLegacyTargetLabel(trimmed) ?: trimmed.removeSurrounding("\""))
        return nameRefKey(trimmed)
    }

    private fun autoRegisterRefs(block: ActivityBlock) {
        val action = block as? ActivityBlock.Action ?: return
        val label = (action.label as? RichLabel.Plain)?.text?.trim().orEmpty()
        if (label.isEmpty()) return
        currentTarget() += ActivityBlock.Note(RichLabel.Plain(NODE_REF_PREFIX + labelRefKey(label)))
        registerRef(labelRefKey(label))
        if (label.matches(Regex("[A-Za-z0-9_.:-]+"))) {
            currentTarget() += ActivityBlock.Note(RichLabel.Plain(NODE_REF_PREFIX + nameRefKey(label)))
            registerRef(nameRefKey(label))
        }
    }

    private fun labelRefKey(label: String): String = "label:$label"

    private fun nameRefKey(name: String): String = "name:$name"

    private fun syncRefKey(label: String): String = "sync:$label"

    private fun registerRef(ref: String) {
        knownRefs += ref
    }

    private fun applySkinparam(line: String): IrPatchBatch {
        val body = line.substringAfter(" ", "").trim()
        if (body.equals("activity {", ignoreCase = true)) {
            pendingSkinparamBlock = true
            return IrPatchBatch(seq, emptyList())
        }
        if (body.startsWith("activity ", ignoreCase = true)) {
            return applySkinparamEntry(body.removePrefix("activity").trim())
        }
        val key = body.substringBefore(' ', "").trim()
        val value = body.substringAfter(' ', "").trim()
        return when (key.lowercase()) {
            "activitybackgroundcolor" -> storeSkinparam(STYLE_ACTION_FILL_KEY, value)
            "activitybordercolor" -> storeSkinparam(STYLE_ACTION_STROKE_KEY, value)
            "activityfontcolor" -> storeSkinparam(STYLE_ACTION_TEXT_KEY, value)
            "activitydiamondbackgroundcolor" -> storeSkinparam(STYLE_DECISION_FILL_KEY, value)
            "activitydiamondbordercolor" -> storeSkinparam(STYLE_DECISION_STROKE_KEY, value)
            "activitydiamondfontcolor" -> storeSkinparam(STYLE_DECISION_TEXT_KEY, value)
            "activitystartcolor" -> storeSkinparam(STYLE_START_FILL_KEY, value)
            "activityendcolor" -> storeSkinparam(STYLE_STOP_STROKE_KEY, value)
            "activitybarcolor" -> storeSkinparam(STYLE_BAR_FILL_KEY, value)
            "notebackgroundcolor" -> storeSkinparam(STYLE_NOTE_FILL_KEY, value)
            "notebordercolor" -> storeSkinparam(STYLE_NOTE_STROKE_KEY, value)
            "notefontcolor" -> storeSkinparam(STYLE_NOTE_TEXT_KEY, value)
            "arrowcolor" -> storeSkinparam(STYLE_EDGE_COLOR_KEY, value)
            else -> warnUnsupportedSkinparam(line)
        }
    }

    private fun applySkinparamEntry(line: String): IrPatchBatch {
        val key = line.substringBefore(' ', "").trim()
        val value = line.substringAfter(' ', "").trim()
        return when (key.lowercase()) {
            "backgroundcolor" -> storeSkinparam(STYLE_ACTION_FILL_KEY, value)
            "bordercolor" -> storeSkinparam(STYLE_ACTION_STROKE_KEY, value)
            "fontcolor" -> storeSkinparam(STYLE_ACTION_TEXT_KEY, value)
            "diamondbackgroundcolor" -> storeSkinparam(STYLE_DECISION_FILL_KEY, value)
            "diamondbordercolor" -> storeSkinparam(STYLE_DECISION_STROKE_KEY, value)
            "diamondfontcolor" -> storeSkinparam(STYLE_DECISION_TEXT_KEY, value)
            "startcolor" -> storeSkinparam(STYLE_START_FILL_KEY, value)
            "endcolor" -> storeSkinparam(STYLE_STOP_STROKE_KEY, value)
            "barcolor" -> storeSkinparam(STYLE_BAR_FILL_KEY, value)
            "barfontcolor" -> storeSkinparam(STYLE_BAR_TEXT_KEY, value)
            "notebackgroundcolor" -> storeSkinparam(STYLE_NOTE_FILL_KEY, value)
            "notebordercolor" -> storeSkinparam(STYLE_NOTE_STROKE_KEY, value)
            "notefontcolor" -> storeSkinparam(STYLE_NOTE_TEXT_KEY, value)
            "arrowcolor" -> storeSkinparam(STYLE_EDGE_COLOR_KEY, value)
            else -> warnUnsupportedSkinparam("skinparam activity $line")
        }
    }

    private fun storeSkinparam(key: String, value: String): IrPatchBatch {
        if (value.isBlank()) return warnUnsupportedSkinparam("skinparam $key")
        styleExtras[key] = value
        return IrPatchBatch(seq, emptyList())
    }

    private fun warnUnsupportedSkinparam(line: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.WARNING, "Unsupported '$line' ignored", "PLANTUML-W001"))))

    private fun extractParenCondition(body: String): String? =
        Regex("^\\s*\\((.*?)\\)").find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun buildIfElse(branches: List<IfBranch>): ActivityBlock.IfElse {
        require(branches.isNotEmpty())
        val first = branches.first()
        val elseBranch = when {
            branches.size == 1 -> emptyList()
            branches[1].cond == null -> branches[1].blocks.toList()
            else -> listOf(buildIfElse(branches.drop(1)))
        }
        return ActivityBlock.IfElse(
            cond = first.cond ?: RichLabel.Plain("else"),
            thenBranch = first.blocks.toList(),
            elseBranch = elseBranch,
        )
    }

    private fun laneMarker(name: String): ActivityBlock.Note {
        val color = partitionStack.lastOrNull { it.name == name }?.color
        val payload = if (color == null) name else name + LANE_STYLE_SEPARATOR + color
        return ActivityBlock.Note(RichLabel.Plain(SWIMLANE_PREFIX + payload))
    }

    private fun lastLaneMarker(blocks: List<ActivityBlock>): ActivityBlock.Note? {
        for (index in blocks.indices.reversed()) {
            val note = blocks[index] as? ActivityBlock.Note ?: continue
            val text = (note.text as? RichLabel.Plain)?.text ?: continue
            if (text.startsWith(SWIMLANE_PREFIX)) return note
        }
        return null
    }

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E007"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }
}
