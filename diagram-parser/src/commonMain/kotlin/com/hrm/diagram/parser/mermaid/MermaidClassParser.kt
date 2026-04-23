package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassMember
import com.hrm.diagram.core.ir.ClassNamespace
import com.hrm.diagram.core.ir.ClassNode
import com.hrm.diagram.core.ir.ClassNote
import com.hrm.diagram.core.ir.ClassParam
import com.hrm.diagram.core.ir.ClassRelation
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.CssClassDef
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NotePlacement
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.ir.Visibility
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for the Mermaid `classDiagram` Phase-3 subset.
 *
 * State machine: lines are accepted one at a time; when a `class Foo {` line is seen the parser
 * enters a body mode and accumulates member lines until matching `}`. Likewise for `namespace`.
 */
class MermaidClassParser {
    private val classOrder: LinkedHashMap<NodeId, ClassNode> = LinkedHashMap()
    private val relations: MutableList<ClassRelation> = ArrayList()
    private val namespaces: MutableList<ClassNamespace> = ArrayList()
    private val notes: MutableList<ClassNote> = ArrayList()
    private val cssClasses: MutableList<CssClassDef> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var direction: Direction? = null

    private var headerSeen: Boolean = false
    private var seq: Long = 0

    private sealed interface BodyContext {
        data class ClassBody(val id: NodeId) : BodyContext
        data class NamespaceBody(val name: String, val members: MutableList<NodeId> = ArrayList()) : BodyContext
    }

    private val bodyStack: ArrayDeque<BodyContext> = ArrayDeque()

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())

        val errs = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errs != null) return errorBatch("Lex error at ${errs.start}: ${errs.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.CLASS_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'classDiagram' header")
        }

        // Inside a class body, lines are member lines unless they close.
        val ctx = bodyStack.lastOrNull()
        if (ctx is BodyContext.ClassBody) {
            if (toks.size == 1 && toks[0].kind == MermaidTokenKind.RBRACE) {
                bodyStack.removeLast()
                return IrPatchBatch(seq, emptyList())
            }
            return parseMemberInto(ctx.id, toks)
        }
        if (ctx is BodyContext.NamespaceBody) {
            if (toks.size == 1 && toks[0].kind == MermaidTokenKind.RBRACE) {
                bodyStack.removeLast()
                namespaces += ClassNamespace(id = ctx.name, members = ctx.members.toList())
                return IrPatchBatch(seq, emptyList())
            }
            // Inside namespace: only `class Foo` declarations supported.
            return parseStatementInNamespace(ctx, toks)
        }

        return parseStatement(toks)
    }

    fun snapshot(): ClassIR {
        return ClassIR(
            classes = classOrder.values.toList(),
            relations = relations.toList(),
            namespaces = namespaces.toList(),
            notes = notes.toList(),
            cssClasses = cssClasses.toList(),
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(direction = direction),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    // --- statements ---

    private fun parseStatement(toks: List<Token>): IrPatchBatch {
        val first = toks.first()
        return when (first.kind) {
            MermaidTokenKind.CLASS_KW -> parseClassDecl(toks)
            MermaidTokenKind.NAMESPACE_KW -> parseNamespace(toks)
            MermaidTokenKind.NOTE_KW -> parseNote(toks)
            MermaidTokenKind.CSS_CLASS_KW -> parseCssClass(toks)
            MermaidTokenKind.DIRECTION_KW -> parseDirection(toks)
            MermaidTokenKind.IDENT -> parseRelationOrDotted(toks)
            else -> errorBatch("Unexpected token '${first.text}' at start of statement")
        }
    }

    private fun parseStatementInNamespace(ctx: BodyContext.NamespaceBody, toks: List<Token>): IrPatchBatch {
        val first = toks.first()
        return when (first.kind) {
            MermaidTokenKind.CLASS_KW -> {
                val r = parseClassDecl(toks)
                // record member ID
                if (toks.size >= 2 && toks[1].kind == MermaidTokenKind.IDENT) {
                    ctx.members += NodeId(toks[1].text.toString())
                }
                r
            }
            else -> errorBatch("Only 'class' declarations allowed inside namespace")
        }
    }

    private fun parseClassDecl(toks: List<Token>): IrPatchBatch {
        // class Name [~Generics~] [:::cssName] [{ ... }]
        if (toks.size < 2 || toks[1].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected identifier after 'class'")
        }
        val id = NodeId(toks[1].text.toString())
        var idx = 2
        var generics: String? = null
        var cssClass: String? = null

        // generics: ~T~
        if (idx < toks.size && toks[idx].kind == MermaidTokenKind.TILDE) {
            val sb = StringBuilder()
            idx++
            while (idx < toks.size && toks[idx].kind != MermaidTokenKind.TILDE) {
                sb.append(toks[idx].text)
                idx++
            }
            if (idx < toks.size && toks[idx].kind == MermaidTokenKind.TILDE) idx++
            generics = sb.toString()
        }

        // :::cssName
        if (idx < toks.size && toks[idx].kind == MermaidTokenKind.TRIPLE_COLON) {
            idx++
            if (idx < toks.size && toks[idx].kind == MermaidTokenKind.IDENT) {
                cssClass = toks[idx].text.toString()
                idx++
            }
        }

        ensureClass(id, name = id.value, generics = generics, cssClass = cssClass)

        // Optional inline body { ... } or open brace.
        if (idx < toks.size && toks[idx].kind == MermaidTokenKind.LBRACE) {
            idx++
            // Find matching RBRACE on same line, if present.
            val rbraceIdx = findClosingBraceIdx(toks, idx)
            if (rbraceIdx == -1) {
                // Multi-line body — push context.
                bodyStack.addLast(BodyContext.ClassBody(id))
                if (idx < toks.size) {
                    // Inline content before newline (rare).
                    return parseMemberInto(id, toks.subList(idx, toks.size))
                }
                return IrPatchBatch(seq, emptyList())
            } else {
                // Inline: split body by ';' tokens? Mermaid allows multiple members separated by newlines.
                // For inline single-line, treat the inner tokens as one member line if any.
                if (rbraceIdx > idx) {
                    parseMemberInto(id, toks.subList(idx, rbraceIdx))
                }
                return IrPatchBatch(seq, emptyList())
            }
        }

        return IrPatchBatch(seq, emptyList())
    }

    private fun findClosingBraceIdx(toks: List<Token>, fromIdx: Int): Int {
        for (i in fromIdx until toks.size) {
            if (toks[i].kind == MermaidTokenKind.RBRACE) return i
        }
        return -1
    }

    private fun parseNamespace(toks: List<Token>): IrPatchBatch {
        // namespace Name { ... }
        if (toks.size < 3 || toks[1].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected 'namespace <Name> {'")
        }
        val name = toks[1].text.toString()
        if (toks[2].kind != MermaidTokenKind.LBRACE) {
            return errorBatch("Expected '{' after namespace name")
        }
        bodyStack.addLast(BodyContext.NamespaceBody(name))
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseNote(toks: List<Token>): IrPatchBatch {
        // Supported forms:
        //   note "text"
        //   note for ClassName "text"                        (Mermaid native)
        //   note left  of ClassName : text                   (sequence-style extension)
        //   note right of ClassName : text
        //   note top   of ClassName : text
        //   note bottom of ClassName : text
        //   note (left|right|top|bottom) of ClassName "text" (string form also accepted)
        var idx = 1
        var target: NodeId? = null
        var placement: NotePlacement = NotePlacement.Standalone

        // Directional prefix: `(left|right|top|bottom) of <Ident>`
        val dirKind = toks.getOrNull(idx)?.kind
        if (dirKind == MermaidTokenKind.LEFT_KW || dirKind == MermaidTokenKind.RIGHT_KW ||
            dirKind == MermaidTokenKind.TOP_KW || dirKind == MermaidTokenKind.BOTTOM_KW
        ) {
            placement = when (dirKind) {
                MermaidTokenKind.LEFT_KW -> NotePlacement.LeftOf
                MermaidTokenKind.RIGHT_KW -> NotePlacement.RightOf
                MermaidTokenKind.TOP_KW -> NotePlacement.TopOf
                MermaidTokenKind.BOTTOM_KW -> NotePlacement.BottomOf
                else -> NotePlacement.RightOf
            }
            idx++
            if (toks.getOrNull(idx)?.kind != MermaidTokenKind.OF_KW) {
                return errorBatch("Expected 'of' after note direction")
            }
            idx++
            if (idx >= toks.size || toks[idx].kind != MermaidTokenKind.IDENT) {
                return errorBatch("Expected identifier after 'note <dir> of'")
            }
            target = NodeId(toks[idx].text.toString())
            idx++
        } else if (toks.getOrNull(idx)?.kind == MermaidTokenKind.OF_KW) {
            // `note for <Ident>` (OF_KW also matches "for" in lexer).
            idx++
            if (idx >= toks.size || toks[idx].kind != MermaidTokenKind.IDENT) {
                return errorBatch("Expected identifier after 'note for'")
            }
            target = NodeId(toks[idx].text.toString())
            idx++
            placement = NotePlacement.RightOf
        }

        // Body: either a STRING or `: free text until end of line`.
        val text: String = when {
            idx < toks.size && toks[idx].kind == MermaidTokenKind.STRING ->
                toks[idx].text.toString()
            idx < toks.size && toks[idx].kind == MermaidTokenKind.COLON -> {
                idx++
                toks.subList(idx, toks.size).joinToString(" ") { it.text.toString() }.trim()
            }
            else -> return errorBatch("Expected note text (quoted string or ': text')")
        }

        if (target == null) placement = NotePlacement.Standalone
        notes += ClassNote(
            text = RichLabel.of(text),
            targetClass = target,
            placement = placement,
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseCssClass(toks: List<Token>): IrPatchBatch {
        // cssClass "A,B" styleName
        if (toks.size < 3 || toks[1].kind != MermaidTokenKind.STRING) {
            return errorBatch("Expected: cssClass \"name1,name2\" styleName")
        }
        val targets = toks[1].text.toString()
        val styleTok = toks[2]
        if (styleTok.kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected style name after cssClass targets")
        }
        cssClasses += CssClassDef(name = targets, style = styleTok.text.toString())
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseDirection(toks: List<Token>): IrPatchBatch {
        if (toks.size < 2 || toks[1].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected direction (TB/LR/RL/BT)")
        }
        val d = when (toks[1].text.toString()) {
            "TB", "TD" -> Direction.TB
            "BT" -> Direction.BT
            "LR" -> Direction.LR
            "RL" -> Direction.RL
            else -> return errorBatch("Unknown direction '${toks[1].text}'")
        }
        direction = d
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseRelationOrDotted(toks: List<Token>): IrPatchBatch {
        // Forms:
        //  IDENT [STRING] <arrow> [STRING] IDENT [: label]
        //  IDENT : memberLine
        if (toks.size >= 3 && toks[1].kind == MermaidTokenKind.COLON) {
            // Dotted-member form
            val id = NodeId(toks[0].text.toString())
            ensureClass(id, name = id.value)
            return parseMemberInto(id, toks.subList(2, toks.size))
        }

        val from = NodeId(toks[0].text.toString())
        var idx = 1
        var fromCard: String? = null
        if (idx < toks.size && toks[idx].kind == MermaidTokenKind.STRING) {
            fromCard = toks[idx].text.toString(); idx++
        }
        if (idx >= toks.size) return errorBatch("Truncated statement after '${toks[0].text}'")
        val arrowTok = toks[idx]
        val (kind, reverse) = arrowKindFor(arrowTok.kind) ?: return errorBatch(
            "Expected class arrow, got '${arrowTok.text}'"
        )
        idx++
        var toCard: String? = null
        if (idx < toks.size && toks[idx].kind == MermaidTokenKind.STRING) {
            toCard = toks[idx].text.toString(); idx++
        }
        if (idx >= toks.size || toks[idx].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected target identifier")
        }
        val to = NodeId(toks[idx].text.toString())
        idx++
        var label: RichLabel = RichLabel.Empty
        if (idx < toks.size && toks[idx].kind == MermaidTokenKind.COLON) {
            // Reassemble label tokens.
            val sb = StringBuilder()
            for (i in (idx + 1) until toks.size) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(toks[i].text)
            }
            val s = sb.toString().trim()
            if (s.isNotEmpty()) label = RichLabel.Plain(s)
        }
        ensureClass(from, name = from.value)
        ensureClass(to, name = to.value)
        val (a, b, fc, tc) = if (reverse) {
            // Swap so that semantic "owner" / "child" reads consistently.
            ListedFour(to, from, toCard, fromCard)
        } else ListedFour(from, to, fromCard, toCard)
        relations += ClassRelation(
            from = a, to = b, kind = kind,
            fromCardinality = fc, toCardinality = tc, label = label,
        )
        return IrPatchBatch(seq, emptyList())
    }

    private data class ListedFour(val a: NodeId, val b: NodeId, val c: String?, val d: String?)

    private fun arrowKindFor(kind: Int): Pair<ClassRelationKind, Boolean>? = when (kind) {
        MermaidTokenKind.CLASS_ARROW_INHERIT -> ClassRelationKind.Inheritance to false
        MermaidTokenKind.CLASS_ARROW_COMPOSITION -> ClassRelationKind.Composition to false
        MermaidTokenKind.CLASS_ARROW_AGGREGATION -> ClassRelationKind.Aggregation to false
        MermaidTokenKind.CLASS_ARROW_ASSOCIATION -> ClassRelationKind.Association to false
        MermaidTokenKind.CLASS_ARROW_DEPENDENCY -> ClassRelationKind.Dependency to false
        MermaidTokenKind.CLASS_ARROW_REALIZATION -> ClassRelationKind.Realization to false
        MermaidTokenKind.CLASS_LINK_SOLID -> ClassRelationKind.Link to false
        MermaidTokenKind.CLASS_LINK_DASHED -> ClassRelationKind.LinkDashed to false
        else -> null
    }

    private fun parseMemberInto(classId: NodeId, toks: List<Token>): IrPatchBatch {
        // Grammar (loose):
        //   <<stereotype>>            → set stereotype
        //   [+|-|#|~] name (params) [: ReturnType] [$] [*]
        //   [+|-|#|~] type name [$] [*]
        //   [+|-|#|~] name : type [$] [*]
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())

        // Stereotype line: <<text>>
        if (toks[0].kind == MermaidTokenKind.STEREOTYPE_OPEN) {
            val sb = StringBuilder()
            var i = 1
            while (i < toks.size && toks[i].kind != MermaidTokenKind.STEREOTYPE_CLOSE) {
                sb.append(toks[i].text)
                i++
            }
            updateClass(classId) { it.copy(stereotype = sb.toString()) }
            return IrPatchBatch(seq, emptyList())
        }

        var i = 0
        // Visibility
        var visibility = Visibility.PACKAGE
        when {
            toks[0].kind == MermaidTokenKind.MINUS -> { visibility = Visibility.PRIVATE; i++ }
            toks[0].kind == MermaidTokenKind.PLUS -> { visibility = Visibility.PUBLIC; i++ }
            toks[0].kind == MermaidTokenKind.HASH -> { visibility = Visibility.PROTECTED; i++ }
            toks[0].kind == MermaidTokenKind.TILDE -> { visibility = Visibility.PACKAGE; i++ }
        }
        if (i >= toks.size) return IrPatchBatch(seq, emptyList())

        var isStatic = false
        var isAbstract = false

        // Detect method by looking for LPAREN within the line.
        val lparen = toks.indexOfFirst { it.kind == MermaidTokenKind.LPAREN }
        val rparen = toks.indexOfFirst { it.kind == MermaidTokenKind.RPAREN }
        if (lparen >= 0 && rparen > lparen) {
            // Method form. Tokens before LPAREN: optional return-type words then name (last token).
            val beforeName = toks.subList(i, lparen)
            if (beforeName.isEmpty()) return errorBatch("Empty member declaration")
            val name = beforeName.last().text.toString()
            // Return type may appear AFTER the closing paren as ` : Type` or ` Type`.
            val params = parseParamList(toks.subList(lparen + 1, rparen))
            var returnType: String? = null
            var k = rparen + 1
            // Skip COLON if present
            if (k < toks.size && toks[k].kind == MermaidTokenKind.COLON) k++
            val sb = StringBuilder()
            while (k < toks.size) {
                val t = toks[k]
                if (t.kind == MermaidTokenKind.DOLLAR) { isStatic = true; k++; continue }
                if (t.kind == MermaidTokenKind.ASTERISK) { isAbstract = true; k++; continue }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(t.text)
                k++
            }
            if (sb.isNotEmpty()) returnType = sb.toString()
            addMember(
                classId,
                ClassMember(
                    visibility = visibility,
                    name = name,
                    type = returnType,
                    params = params,
                    isMethod = true,
                    isStatic = isStatic,
                    isAbstract = isAbstract,
                ),
            )
            return IrPatchBatch(seq, emptyList())
        }

        // Attribute form. Two shapes:
        //   Type name [$] [*]
        //   name : Type [$] [*]
        val rest = toks.subList(i, toks.size).filter {
            if (it.kind == MermaidTokenKind.DOLLAR) { isStatic = true; false }
            else if (it.kind == MermaidTokenKind.ASTERISK) { isAbstract = true; false }
            else true
        }
        if (rest.isEmpty()) return IrPatchBatch(seq, emptyList())

        val colonIdx = rest.indexOfFirst { it.kind == MermaidTokenKind.COLON }
        val name: String; val type: String?
        if (colonIdx >= 0) {
            name = rest.subList(0, colonIdx).joinToString(" ") { it.text.toString() }
            type = rest.subList(colonIdx + 1, rest.size).joinToString(" ") { it.text.toString() }
                .ifEmpty { null }
        } else if (rest.size == 1) {
            name = rest[0].text.toString(); type = null
        } else {
            // First N-1 tokens = type, last = name
            type = rest.subList(0, rest.size - 1).joinToString(" ") { it.text.toString() }
            name = rest.last().text.toString()
        }

        addMember(
            classId,
            ClassMember(
                visibility = visibility,
                name = name,
                type = type,
                isMethod = false,
                isStatic = isStatic,
                isAbstract = isAbstract,
            ),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseParamList(toks: List<Token>): List<ClassParam> {
        if (toks.isEmpty()) return emptyList()
        // Split by comma at top level.
        val out = ArrayList<ClassParam>()
        val cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.COMMA) {
                if (cur.isNotEmpty()) { out += paramFromTokens(cur); cur.clear() }
            } else cur += t
        }
        if (cur.isNotEmpty()) out += paramFromTokens(cur)
        return out
    }

    private fun paramFromTokens(toks: List<Token>): ClassParam {
        if (toks.size == 1) return ClassParam(name = toks[0].text.toString())
        // "Type name" or "name : Type"
        val colonIdx = toks.indexOfFirst { it.kind == MermaidTokenKind.COLON }
        return if (colonIdx >= 0) {
            val name = toks.subList(0, colonIdx).joinToString(" ") { it.text.toString() }
            val type = toks.subList(colonIdx + 1, toks.size).joinToString(" ") { it.text.toString() }
            ClassParam(name = name, type = type.ifEmpty { null })
        } else {
            val type = toks.subList(0, toks.size - 1).joinToString(" ") { it.text.toString() }
            val name = toks.last().text.toString()
            ClassParam(name = name, type = type)
        }
    }

    private fun ensureClass(id: NodeId, name: String, generics: String? = null, cssClass: String? = null) {
        val existing = classOrder[id]
        if (existing == null) {
            classOrder[id] = ClassNode(id = id, name = name, generics = generics, cssClass = cssClass)
        } else {
            classOrder[id] = existing.copy(
                name = if (name.isNotEmpty()) name else existing.name,
                generics = generics ?: existing.generics,
                cssClass = cssClass ?: existing.cssClass,
            )
        }
    }

    private fun updateClass(id: NodeId, transform: (ClassNode) -> ClassNode) {
        val existing = classOrder[id] ?: ClassNode(id = id, name = id.value)
        classOrder[id] = transform(existing)
    }

    private fun addMember(id: NodeId, m: ClassMember) {
        val existing = classOrder[id] ?: ClassNode(id = id, name = id.value)
        classOrder[id] = existing.copy(members = existing.members + m)
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MMD-C001")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
