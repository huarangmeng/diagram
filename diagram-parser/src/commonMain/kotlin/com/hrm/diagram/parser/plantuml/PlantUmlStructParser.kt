package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
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

    companion object {
        const val COLLAPSIBLE_PATHS_KEY = "plantuml.struct.collapsiblePaths"
        const val SCALAR_KINDS_KEY = "plantuml.struct.scalarKinds"
    }

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

    fun snapshot(): StructIR {
        val root = parseBody(reportErrors = false).node
        return StructIR(
            root = root,
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(extras = buildExtras(root)),
        )
    }

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

    private fun buildExtras(root: StructNode): Map<String, String> {
        val collapsible = ArrayList<String>()
        val scalarKinds = ArrayList<String>()
        fun visit(node: StructNode, path: String) {
            when (node) {
                is StructNode.ArrayNode -> {
                    collapsible += path
                    node.items.forEachIndexed { index, child -> visit(child, "$path.$index") }
                }
                is StructNode.ObjectNode -> {
                    collapsible += path
                    node.entries.forEachIndexed { index, child -> visit(child, "$path.$index") }
                }
                is StructNode.Scalar -> scalarKinds += "$path|${scalarKind(node.value)}"
            }
        }
        visit(root, "root")
        return buildMap {
            if (collapsible.isNotEmpty()) put(COLLAPSIBLE_PATHS_KEY, collapsible.joinToString("||"))
            if (scalarKinds.isNotEmpty()) put(SCALAR_KINDS_KEY, scalarKinds.joinToString("||"))
        }
    }

    private fun scalarKind(value: String): String = when {
        value == "null" || value == "~" -> "null"
        value == "true" || value == "false" -> "boolean"
        value.toDoubleOrNull() != null -> "number"
        else -> "string"
    }

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
                                'u' -> parseUnicodeEscape()
                                else -> escaped
                            },
                        )
                    }
                    else -> out.append(c)
                }
            }
            fail("Unclosed JSON string")
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > source.length) fail("Incomplete JSON unicode escape")
            val hex = source.substring(index, index + 4)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                fail("Invalid JSON unicode escape")
            }
            index += 4
            return hex.toInt(16).toChar()
        }

        private fun parseLiteral(): String {
            val start = index
            while (index < source.length && source[index] !in listOf(',', '}', ']', '\n', '\r', '\t', ' ')) {
                index++
            }
            if (start == index) fail("Expected JSON value")
            val literal = source.substring(start, index)
            if (!isJsonLiteral(literal)) fail("Invalid JSON literal '$literal'")
            return literal
        }

        private fun isJsonLiteral(value: String): Boolean =
            value == "true" ||
                value == "false" ||
                value == "null" ||
                JSON_NUMBER.matches(value)

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

        companion object {
            private val JSON_NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")
        }
    }

    private class YamlReader(private val source: String) {
        private data class Line(val indent: Int, val text: String)

        private val lines = source.lines()
            .mapNotNull { raw ->
                val withoutComment = stripYamlComment(raw).trimEnd()
                val trimmed = withoutComment.trim()
                if (trimmed.isBlank() || trimmed == "---" || trimmed == "...") null else Line(raw.takeWhile { it == ' ' }.length, trimmed)
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
                    entries += StructNode.Scalar(null, normalizeScalar(line.text))
                    continue
                }
                val childKey = line.text.substring(0, split).trim()
                val rawValue = line.text.substring(split + 1).trim()
                entries += if (rawValue.isEmpty()) {
                    if (index < lines.size && lines[index].indent > indent) parseBlock(lines[index].indent, childKey)
                    else StructNode.ObjectNode(childKey, emptyList())
                } else if (isBlockScalarHeader(rawValue)) {
                    parseBlockScalar(childKey, indent, header = rawValue)
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
                    isBlockScalarHeader(value) -> parseBlockScalar(itemKey, indent, header = value)
                    value.startsWith("{") && value.endsWith("}") -> parseInlineValue(itemKey, value)
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
            if (value.isEmpty() && index < lines.size && lines[index].indent > parentIndent) {
                entries += parseBlock(lines[index].indent, key)
                return StructNode.ObjectNode(itemKey, entries)
            }
            entries += parseInlineValue(key, value)
            if (index < lines.size && lines[index].indent > parentIndent) {
                val nested = parseObject(lines[index].indent, null)
                entries += nested.entries
            }
            return StructNode.ObjectNode(itemKey, entries)
        }

        private fun parseBlockScalar(key: String?, parentIndent: Int, header: String): StructNode.Scalar {
            val parts = ArrayList<String>()
            while (index < lines.size && lines[index].indent > parentIndent) {
                parts += lines[index].text
                index++
            }
            val folded = header.startsWith(">")
            val chomp = header.lastOrNull().takeIf { it == '-' || it == '+' }
            val body = if (folded) parts.joinToString(" ") else parts.joinToString("\n")
            val value = when (chomp) {
                '+' -> body + "\n"
                '-' -> body.trimEnd('\n')
                else -> body
            }
            return StructNode.Scalar(key, value)
        }

        private fun parseInlineValue(key: String?, value: String): StructNode {
            if (value.startsWith("[") && value.endsWith("]")) {
                val items = splitTopLevel(value.removePrefix("[").removeSuffix("]"))
                    .mapIndexed { idx, item -> withArrayKey(parseInlineValue(null, item.trim()), "[$idx]") }
                return StructNode.ArrayNode(key, items)
            }
            if (value.startsWith("{") && value.endsWith("}")) {
                val entries = splitTopLevel(value.removePrefix("{").removeSuffix("}"))
                    .mapNotNull { entry ->
                        val split = findTopLevelColon(entry)
                        if (split <= 0) null else parseInlineValue(entry.substring(0, split).trim(), entry.substring(split + 1).trim())
                    }
                return StructNode.ObjectNode(key, entries)
            }
            return StructNode.Scalar(key, normalizeScalar(value))
        }

        private fun withArrayKey(node: StructNode, key: String): StructNode = when (node) {
            is StructNode.ArrayNode -> node.copy(key = key)
            is StructNode.ObjectNode -> node.copy(key = key)
            is StructNode.Scalar -> node.copy(key = key)
        }

        private fun normalizeScalar(value: String): String {
            val trimmed = value.trim()
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
                return unescapeDoubleQuoted(trimmed.substring(1, trimmed.length - 1))
            }
            if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length >= 2) {
                return trimmed.substring(1, trimmed.length - 1).replace("''", "'")
            }
            return when (trimmed.lowercase()) {
                "true", "yes", "on" -> "true"
                "false", "no", "off" -> "false"
                "null", "~" -> "null"
                else -> trimmed
            }
        }

        private fun unescapeDoubleQuoted(value: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c != '\\' || i >= value.length) {
                    out.append(c)
                    continue
                }
                out.append(
                    when (val escaped = value[i++]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '"', '\\', '/' -> escaped
                        else -> escaped
                    },
                )
            }
            return out.toString()
        }

        private fun isBlockScalarHeader(value: String): Boolean =
            BLOCK_SCALAR_HEADER.matches(value)

        private fun stripYamlComment(raw: String): String {
            var quote: Char? = null
            var escaped = false
            for (i in raw.indices) {
                val c = raw[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\' && quote == '"') {
                    escaped = true
                    continue
                }
                if (quote != null) {
                    if (c == quote) quote = null
                    continue
                }
                if (c == '"' || c == '\'') {
                    quote = c
                    continue
                }
                if (c == '#' && (i == 0 || raw[i - 1].isWhitespace())) return raw.substring(0, i)
            }
            return raw
        }

        private fun splitTopLevel(raw: String): List<String> {
            val out = ArrayList<String>()
            var quote: Char? = null
            var escaped = false
            var depth = 0
            var start = 0
            for (i in raw.indices) {
                val c = raw[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\' && quote == '"') {
                    escaped = true
                    continue
                }
                if (quote != null) {
                    if (c == quote) quote = null
                    continue
                }
                when (c) {
                    '"', '\'' -> quote = c
                    '[', '{' -> depth++
                    ']', '}' -> depth--
                    ',' -> if (depth == 0) {
                        out += raw.substring(start, i).trim()
                        start = i + 1
                    }
                }
            }
            val tail = raw.substring(start).trim()
            if (tail.isNotEmpty()) out += tail
            return out
        }

        private fun findTopLevelColon(raw: String): Int {
            var quote: Char? = null
            var escaped = false
            var depth = 0
            for (i in raw.indices) {
                val c = raw[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\' && quote == '"') {
                    escaped = true
                    continue
                }
                if (quote != null) {
                    if (c == quote) quote = null
                    continue
                }
                when (c) {
                    '"', '\'' -> quote = c
                    '[', '{' -> depth++
                    ']', '}' -> depth--
                    ':' -> if (depth == 0) return i
                }
            }
            return -1
        }

        companion object {
            private val BLOCK_SCALAR_HEADER = Regex("""^[|>][+-]?$""")
        }
    }
}
