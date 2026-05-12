package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.WireBox
import com.hrm.diagram.core.ir.WireframeIR
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for the minimal PlantUML Salt wireframe slice.
 *
 * Supported syntax:
 * - `@startsalt ... @endsalt` session blocks
 * - optional `salt` / `salt {` / `{` / `}` wrapper lines
 * - plain text lines
 * - button lines written as `[Button]`
 * - input lines written as `"placeholder"` or `Label: "placeholder"`
 * - dropdown shorthand lines written as `^Choice^`
 * - basic tab rows written as `{* General | Advanced | Help }`
 * - tree blocks written as `{T ... }` with `+` / `++` / `+++` item prefixes
 * - frame blocks written as `{+ Title ... }`, `{# Title ... }`, or `{frame Title ... }`
 * - table grid rows written as `A | B | C`
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlSaltParser()
 * parser.acceptLine("Login")
 * parser.acceptLine("[Submit]")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlSaltParser {
    private data class TreeItem(val level: Int, val label: String)
    private data class MutableTreeNode(val label: String, val children: MutableList<MutableTreeNode> = ArrayList())

    private val children: MutableList<WireBox> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var frameTitle: String? = null
    private var frameChildren: MutableList<WireBox>? = null
    private var treeTitle: String? = null
    private var treeItems: MutableList<TreeItem>? = null
    private val tableRows: MutableList<List<String>> = ArrayList()
    private var seq: Long = 0L

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            flushTableRows()
            return IrPatchBatch(seq, emptyList())
        }
        if (acceptOpenBlockLine(trimmed)) return IrPatchBatch(seq, emptyList())
        if (acceptNestedBlockLine(trimmed)) return IrPatchBatch(seq, emptyList())
        if (isWrapperLine(trimmed)) {
            flushTableRows()
            return IrPatchBatch(seq, emptyList())
        }

        if (acceptTableRow(trimmed)) return IrPatchBatch(seq, emptyList())
        flushTableRows()
        children += parseWidget(trimmed)
        return IrPatchBatch(seq, emptyList())
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        if (blockClosed) return IrPatchBatch(seq, emptyList())
        val d = Diagnostic(
            severity = Severity.ERROR,
            message = "Missing @endsalt closing delimiter",
            code = "PLANTUML-E017",
        )
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    fun snapshot(): WireframeIR =
        WireframeIR(
            root = WireBox.Plain(
                label = RichLabel.Plain("salt"),
                children = children.toList() + openTableSnapshot() + openBlockSnapshots(),
            ),
            sourceLanguage = SourceLanguage.PLANTUML,
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun isWrapperLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower == "salt" ||
            lower == "salt {" ||
            lower == "salt{" ||
            line == "{" ||
            line == "}"
    }

    private fun acceptOpenBlockLine(line: String): Boolean {
        if (frameChildren != null || treeItems != null) return false
        parseTreeStart(line)?.let { title ->
            flushTableRows()
            treeTitle = title
            treeItems = ArrayList()
            return true
        }
        parseFrameStart(line)?.let { title ->
            flushTableRows()
            frameTitle = title
            frameChildren = ArrayList()
            return true
        }
        return false
    }

    private fun acceptNestedBlockLine(line: String): Boolean {
        treeItems?.let { items ->
            if (line == "}") {
                children += buildTreeBox(treeTitle, items)
                treeTitle = null
                treeItems = null
            } else {
                parseTreeItem(line)?.let(items::add)
            }
            return true
        }

        frameChildren?.let { nested ->
            if (line == "}") {
                flushTableRows(nested)
                children += WireBox.Plain(RichLabel.Plain(frameLabel(frameTitle)), nested.toList())
                frameTitle = null
                frameChildren = null
            } else if (!isWrapperLine(line)) {
                parseTableRow(line)?.let {
                    tableRows += it
                    return true
                }
                flushTableRows(nested)
                nested += parseWidget(line)
            }
            return true
        }
        return false
    }

    private fun openBlockSnapshots(): List<WireBox> {
        val open = ArrayList<WireBox>()
        frameChildren?.let { open += WireBox.Plain(RichLabel.Plain(frameLabel(frameTitle)), it.toList() + openTableSnapshot()) }
        treeItems?.let { open += buildTreeBox(treeTitle, it) }
        return open
    }

    private fun acceptTableRow(line: String): Boolean {
        val row = parseTableRow(line) ?: return false
        tableRows += row
        return true
    }

    private fun parseTableRow(line: String): List<String>? {
        if ("|" !in line || line.startsWith("{")) return null
        val cells = line.trim('|')
            .split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return cells.takeIf { it.size >= 2 }
    }

    private fun flushTableRows(target: MutableList<WireBox> = children) {
        if (tableRows.isEmpty()) return
        target += buildTableBox(tableRows)
        tableRows.clear()
    }

    private fun openTableSnapshot(): List<WireBox> =
        if (tableRows.isEmpty()) emptyList() else listOf(buildTableBox(tableRows))

    private fun buildTableBox(rows: List<List<String>>): WireBox =
        WireBox.Plain(
            label = RichLabel.Plain("Table"),
            children = rows.map { row ->
                WireBox.Plain(
                    label = RichLabel.Plain("Row"),
                    children = row.map { cell -> WireBox.Plain(RichLabel.Plain(cell)) },
                )
            },
        )

    private fun parseWidget(line: String): WireBox =
        parseTabbedGroup(line)
            ?: parseInput(line)
            ?: parseButton(line)?.let { WireBox.Button(RichLabel.Plain(it)) }
            ?: parseDropdown(line)?.let { WireBox.Button(RichLabel.Plain("$it v")) }
            ?: WireBox.Plain(RichLabel.Plain(line))

    private fun parseTreeStart(line: String): String? {
        val lower = line.lowercase()
        if (lower != "{t" && !lower.startsWith("{t ")) return null
        return line.drop(2).trim().takeIf { it.isNotEmpty() }
    }

    private fun parseFrameStart(line: String): String? {
        val lower = line.lowercase()
        val rawTitle = when {
            lower.startsWith("{+") -> line.drop(2)
            lower.startsWith("{#") -> line.drop(2)
            lower.startsWith("{frame ") -> line.drop("{frame".length)
            lower == "{frame" -> ""
            else -> return null
        }
        return rawTitle.trim().trimEnd('{').trim().takeIf { it.isNotEmpty() }
    }

    private fun parseTreeItem(line: String): TreeItem? {
        val marker = line.takeWhile { it == '+' || it == '*' }
        if (marker.isEmpty()) return null
        val label = line.drop(marker.length).trim()
        if (label.isEmpty()) return null
        return TreeItem(level = marker.length, label = label)
    }

    private fun buildTreeBox(title: String?, items: List<TreeItem>): WireBox {
        val root = MutableTreeNode(title ?: "Tree")
        val stack = ArrayList<Pair<Int, MutableTreeNode>>()
        stack += 0 to root
        for (item in items) {
            val node = MutableTreeNode(item.label)
            while (stack.isNotEmpty() && stack.last().first >= item.level) {
                stack.removeAt(stack.lastIndex)
            }
            val parent = stack.lastOrNull()?.second ?: root
            parent.children += node
            stack += item.level to node
        }
        return root.toWireBox()
    }

    private fun MutableTreeNode.toWireBox(): WireBox.Plain =
        WireBox.Plain(
            label = RichLabel.Plain(label),
            children = children.map { it.toWireBox() },
        )

    private fun frameLabel(title: String?): String = title?.let { "Frame: $it" } ?: "Frame"

    private fun parseButton(line: String): String? {
        if (!line.startsWith("[") || !line.endsWith("]")) return null
        val label = line.removePrefix("[").removeSuffix("]").trim()
        return label.takeIf { it.isNotEmpty() }
    }

    private fun parseInput(line: String): WireBox.Input? {
        parseQuoted(line)?.let { placeholder ->
            return WireBox.Input(label = RichLabel.Plain(placeholder), placeholder = placeholder)
        }

        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val label = line.substring(0, colon).trim()
        val placeholder = parseQuoted(line.substring(colon + 1).trim()) ?: return null
        if (label.isEmpty() || placeholder.isEmpty()) return null
        return WireBox.Input(label = RichLabel.Plain(label), placeholder = placeholder)
    }

    private fun parseDropdown(line: String): String? {
        if (!line.startsWith("^") || !line.endsWith("^")) return null
        return line.removePrefix("^").removeSuffix("^").trim().takeIf { it.isNotEmpty() }
    }

    private fun parseTabbedGroup(line: String): WireBox.TabbedGroup? {
        if (!line.startsWith("{") || !line.endsWith("}") || "|" !in line) return null
        val body = line.removePrefix("{").removeSuffix("}").trim()
            .removePrefix("*")
            .removePrefix("/")
            .removePrefix("+")
            .trim()
        val tabs = body.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { WireBox.Plain(RichLabel.Plain(it)) }
        if (tabs.size < 2) return null
        return WireBox.TabbedGroup(label = RichLabel.Plain("tabs"), tabs = tabs)
    }

    private fun parseQuoted(text: String): String? {
        if (text.length < 2 || text.first() != '"' || text.last() != '"') return null
        return text.substring(1, text.lastIndex).trim().takeIf { it.isNotEmpty() }
    }
}
