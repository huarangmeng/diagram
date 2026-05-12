package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Lenient line-buffered parser for PlantUML `@startjson` and `@startyaml` structure diagrams.
 *
 * The parser keeps streaming ingestion append-only while snapshots parse the currently buffered
 * body into `StructIR`. Incomplete chunks degrade to an empty object until `finish()` reports the
 * final syntax diagnostic.
 */
@DiagramApi
class PlantUmlStructParser(
    private val format: Format,
) {
    enum class Format { JSON, YAML }

    private val bodyLines: MutableList<String> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0L
    private var finalParseDiagnostics: List<Diagnostic> = emptyList()

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        bodyLines += line
        return IrPatchBatch(seq, emptyList())
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (!blockClosed) {
            val d = Diagnostic(
                severity = Severity.ERROR,
                message = "Missing ${format.endDirective()} closing delimiter",
                code = "PLANTUML-E013",
            )
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
        }
        val parsed = parseBody(reportErrors = true)
        finalParseDiagnostics = parsed.diagnostics
        for (d in parsed.diagnostics) out += IrPatch.AddDiagnostic(d)
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): StructIR =
        StructIR(
            root = parseBody(reportErrors = false).node,
            sourceLanguage = SourceLanguage.PLANTUML,
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics + finalParseDiagnostics

    private fun parseBody(reportErrors: Boolean): ParseOutcome {
        val source = bodyLines.joinToString("\n").trim()
        if (source.isEmpty()) return ParseOutcome(StructNode.ObjectNode(null, emptyList()), emptyList())
        return when (format) {
            Format.JSON -> JsonReader(source).parse(reportErrors)
            Format.YAML -> YamlReader(source).parse(reportErrors)
        }
    }

    private fun Format.endDirective(): String = when (this) {
        Format.JSON -> "@endjson"
        Format.YAML -> "@endyaml"
    }

    private data class ParseOutcome(
        val node: StructNode,
        val diagnostics: List<Diagnostic>,
    )

    private class JsonReader(private val source: String) {
        private var index = 0
        private val diagnostics = ArrayList<Diagnostic>()

        fun parse(reportErrors: Boolean): ParseOutcome {
            val node = runCatching {
                skipWs()
                parseValue(null).also {
                    skipWs()
                    if (index < source.length) fail("Unexpected trailing JSON content")
                }
            }.getOrElse {
                if (reportErrors) diagnostics += diagnostic(it.message ?: "Invalid JSON structure")
                StructNode.ObjectNode(null, emptyList())
            }
            return ParseOutcome(node, diagnostics)
        }

        private fun parseValue(key: String?): StructNode {
            skipWs()
            if (index >= source.length) fail("Unexpected end of JSON input")
            return when (source[index]) {
                '{' -> parseObject(key)
                '[' -> parseArray(key)
                '"' -> StructNode.Scalar(key, parseString())
                else -> StructNode.Scalar(key, parseLiteral())
            }
        }

        private fun parseObject(key: String?): StructNode.ObjectNode {
            expect('{')
            val entries = ArrayList<StructNode>()
            skipWs()
            if (peek('}')) {
                index++
                return StructNode.ObjectNode(key, entries)
            }
            while (true) {
                skipWs()
                if (!peek('"')) fail("Expected JSON object key")
                val childKey = parseString()
                skipWs()
                expect(':')
                entries += parseValue(childKey)
                skipWs()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return StructNode.ObjectNode(key, entries)
                    }
                    else -> fail("Expected ',' or '}' in JSON object")
                }
            }
        }

        private fun parseArray(key: String?): StructNode.ArrayNode {
            expect('[')
            val items = ArrayList<StructNode>()
            var itemIndex = 0
            skipWs()
            if (peek(']')) {
                index++
                return StructNode.ArrayNode(key, items)
            }
            while (true) {
                items += withArrayKey(parseValue("[$itemIndex]"), "[$itemIndex]")
                itemIndex++
                skipWs()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return StructNode.ArrayNode(key, items)
                    }
                    else -> fail("Expected ',' or ']' in JSON array")
                }
            }
        }

        private fun withArrayKey(node: StructNode, key: String): StructNode = when (node) {
            is StructNode.ArrayNode -> node.copy(key = key)
            is StructNode.ObjectNode -> node.copy(key = key)
            is StructNode.Scalar -> node.copy(key = key)
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < source.length) {
                val c = source[index++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= source.length) fail("Unclosed JSON escape")
                        out.append(
                            when (val escaped = source[index++]) {
                                '"', '\\', '/' -> escaped
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> escaped
                            },
                        )
                    }
                    else -> out.append(c)
                }
            }
            fail("Unclosed JSON string")
        }

        private fun parseLiteral(): String {
            val start = index
            while (index < source.length && source[index] !in listOf(',', '}', ']', '\n', '\r', '\t', ' ')) {
                index++
            }
            if (start == index) fail("Expected JSON value")
            return source.substring(start, index)
        }

        private fun skipWs() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun expect(c: Char) {
            if (!peek(c)) fail("Expected '$c'")
            index++
        }

        private fun peek(c: Char): Boolean = index < source.length && source[index] == c

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

        private fun diagnostic(message: String): Diagnostic =
            Diagnostic(severity = Severity.ERROR, message = message, code = "PLANTUML-E013")
    }

    private class YamlReader(private val source: String) {
        private data class Line(val indent: Int, val text: String)

        private val lines = source.lines()
            .mapNotNull { raw ->
                val withoutComment = raw.substringBefore("#").trimEnd()
                if (withoutComment.isBlank()) null else Line(raw.takeWhile { it == ' ' }.length, withoutComment.trim())
            }
        private var index = 0
        private val diagnostics = ArrayList<Diagnostic>()

        fun parse(reportErrors: Boolean): ParseOutcome {
            val node = runCatching {
                if (lines.isEmpty()) StructNode.ObjectNode(null, emptyList()) else parseBlock(lines[index].indent, null)
            }.getOrElse {
                if (reportErrors) diagnostics += Diagnostic(
                    severity = Severity.ERROR,
                    message = it.message ?: "Invalid YAML structure",
                    code = "PLANTUML-E013",
                )
                StructNode.ObjectNode(null, emptyList())
            }
            return ParseOutcome(node, diagnostics)
        }

        private fun parseBlock(indent: Int, key: String?): StructNode {
            if (index >= lines.size || lines[index].indent < indent) return StructNode.ObjectNode(key, emptyList())
            return if (lines[index].text.startsWith("- ")) parseArray(indent, key) else parseObject(indent, key)
        }

        private fun parseObject(indent: Int, key: String?): StructNode.ObjectNode {
            val entries = ArrayList<StructNode>()
            while (index < lines.size && lines[index].indent == indent && !lines[index].text.startsWith("- ")) {
                val line = lines[index++]
                val split = line.text.indexOf(':')
                if (split <= 0) {
                    entries += StructNode.Scalar(null, unquote(line.text))
                    continue
                }
                val childKey = line.text.substring(0, split).trim()
                val rawValue = line.text.substring(split + 1).trim()
                entries += if (rawValue.isEmpty()) {
                    if (index < lines.size && lines[index].indent > indent) parseBlock(lines[index].indent, childKey)
                    else StructNode.ObjectNode(childKey, emptyList())
                } else if (rawValue == "|" || rawValue == ">") {
                    parseBlockScalar(childKey, indent, folded = rawValue == ">")
                } else {
                    parseInlineValue(childKey, rawValue)
                }
            }
            return StructNode.ObjectNode(key, entries)
        }

        private fun parseArray(indent: Int, key: String?): StructNode.ArrayNode {
            val items = ArrayList<StructNode>()
            var itemIndex = 0
            while (index < lines.size && lines[index].indent == indent && lines[index].text.startsWith("- ")) {
                val value = lines[index].text.removePrefix("- ").trim()
                index++
                val itemKey = "[$itemIndex]"
                items += when {
                    value.isEmpty() ->
                        if (index < lines.size && lines[index].indent > indent) parseBlock(lines[index].indent, itemKey)
                        else StructNode.ObjectNode(itemKey, emptyList())
                    value == "|" || value == ">" -> parseBlockScalar(itemKey, indent, folded = value == ">")
                    value.contains(":") && !value.startsWith("\"") && !value.startsWith("'") ->
                        parseInlineObject(itemKey, value, indent)
                    else -> parseInlineValue(itemKey, value)
                }
                itemIndex++
            }
            return StructNode.ArrayNode(key, items)
        }

        private fun parseInlineObject(itemKey: String, firstEntry: String, parentIndent: Int): StructNode.ObjectNode {
            val entries = ArrayList<StructNode>()
            val split = firstEntry.indexOf(':')
            val key = firstEntry.substring(0, split).trim()
            val value = firstEntry.substring(split + 1).trim()
            entries += if (value.isEmpty()) StructNode.ObjectNode(key, emptyList()) else parseInlineValue(key, value)
            if (index < lines.size && lines[index].indent > parentIndent) {
                val nested = parseObject(lines[index].indent, null)
                entries += nested.entries
            }
            return StructNode.ObjectNode(itemKey, entries)
        }

        private fun parseBlockScalar(key: String?, parentIndent: Int, folded: Boolean): StructNode.Scalar {
            val parts = ArrayList<String>()
            while (index < lines.size && lines[index].indent > parentIndent) {
                parts += lines[index].text
                index++
            }
            return StructNode.Scalar(key, if (folded) parts.joinToString(" ") else parts.joinToString("\n"))
        }

        private fun parseInlineValue(key: String?, value: String): StructNode {
            if (value.startsWith("[") && value.endsWith("]")) {
                val items = value.removePrefix("[").removeSuffix("]")
                    .split(',')
                    .mapIndexed { idx, item -> StructNode.Scalar("[$idx]", unquote(item.trim())) }
                return StructNode.ArrayNode(key, items)
            }
            return StructNode.Scalar(key, unquote(value))
        }

        private fun unquote(value: String): String =
            value.removeSurrounding("\"").removeSurrounding("'")
    }
}
