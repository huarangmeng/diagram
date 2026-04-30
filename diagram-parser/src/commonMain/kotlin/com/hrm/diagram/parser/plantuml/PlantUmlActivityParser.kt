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
    }

    private sealed interface Frame {
        val target: MutableList<ActivityBlock>

        data class Root(override val target: MutableList<ActivityBlock>) : Frame

        data class IfFrame(
            val cond: RichLabel,
            val thenBranch: MutableList<ActivityBlock> = mutableListOf(),
            val elseBranch: MutableList<ActivityBlock> = mutableListOf(),
            var inElse: Boolean = false,
        ) : Frame {
            override val target: MutableList<ActivityBlock> get() = if (inElse) elseBranch else thenBranch
        }

        data class WhileFrame(
            val cond: RichLabel,
            val body: MutableList<ActivityBlock> = mutableListOf(),
        ) : Frame {
            override val target: MutableList<ActivityBlock> get() = body
        }
    }

    private val rootBlocks: MutableList<ActivityBlock> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val frames: ArrayDeque<Frame> = ArrayDeque<Frame>().apply { addLast(Frame.Root(rootBlocks)) }

    private var seq: Long = 0
    private var hasStart: Boolean = false
    private var hasStop: Boolean = false

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }

        return when {
            trimmed.equals("start", ignoreCase = true) -> {
                hasStart = true
                IrPatchBatch(seq, emptyList())
            }
            trimmed.equals("stop", ignoreCase = true) || trimmed.equals("end", ignoreCase = true) -> {
                hasStop = true
                IrPatchBatch(seq, emptyList())
            }
            trimmed.startsWith(":") && trimmed.endsWith(";") -> addBlock(ActivityBlock.Action(RichLabel.Plain(trimmed.removePrefix(":").removeSuffix(";").trim())))
            trimmed.startsWith("if ", ignoreCase = true) || trimmed.startsWith("if(", ignoreCase = true) -> openIf(trimmed)
            trimmed.startsWith("else", ignoreCase = true) -> switchElse()
            trimmed.equals("endif", ignoreCase = true) -> closeIf()
            trimmed.startsWith("while ", ignoreCase = true) || trimmed.startsWith("while(", ignoreCase = true) -> openWhile(trimmed)
            trimmed.equals("endwhile", ignoreCase = true) -> closeWhile()
            trimmed.startsWith("note ", ignoreCase = true) || trimmed.startsWith("note:", ignoreCase = true) -> addNote(trimmed)
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
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): ActivityIR = ActivityIR(
        blocks = rootBlocks.toList(),
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(
            extras = buildMap {
                put(HAS_START_KEY, hasStart.toString())
                put(HAS_STOP_KEY, hasStop.toString())
            },
        ),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun currentTarget(): MutableList<ActivityBlock> = frames.last().target

    private fun addBlock(block: ActivityBlock): IrPatchBatch {
        currentTarget() += block
        return IrPatchBatch(seq, emptyList())
    }

    private fun addNote(line: String): IrPatchBatch {
        val text = when {
            line.startsWith("note:", ignoreCase = true) -> line.substringAfter(':').trim()
            ':' in line -> line.substringAfter(':').trim()
            else -> line.removePrefix("note").trim()
        }
        return addBlock(ActivityBlock.Note(RichLabel.Plain(text)))
    }

    private fun openIf(line: String): IrPatchBatch {
        val cond = Regex("^if\\s*\\((.*?)\\)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.trim()
            ?: return errorBatch("Invalid PlantUML activity if syntax: $line")
        frames.addLast(Frame.IfFrame(cond = RichLabel.Plain(cond)))
        return IrPatchBatch(seq, emptyList())
    }

    private fun switchElse(): IrPatchBatch {
        val frame = frames.lastOrNull() as? Frame.IfFrame ?: return errorBatch("'else' without matching 'if'")
        frame.inElse = true
        return IrPatchBatch(seq, emptyList())
    }

    private fun closeIf(): IrPatchBatch {
        val frame = frames.removeLastOrNull() as? Frame.IfFrame ?: return errorBatch("'endif' without matching 'if'")
        currentTarget() += ActivityBlock.IfElse(
            cond = frame.cond,
            thenBranch = frame.thenBranch.toList(),
            elseBranch = frame.elseBranch.toList(),
        )
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

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E007"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }
}
