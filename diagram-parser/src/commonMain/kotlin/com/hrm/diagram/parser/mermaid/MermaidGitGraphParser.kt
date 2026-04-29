package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.GitCommit
import com.hrm.diagram.core.ir.GitCommitType
import com.hrm.diagram.core.ir.GitGraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `gitGraph`.
 *
 * Supported subset:
 * - `gitGraph` header, optional orientation suffix ignored
 * - `commit [id: ...] [type: ...] [tag: ...]`
 * - `branch <name>`
 * - `checkout <name>` / `switch <name>`
 * - `merge <name> [id: ...] [type: ...] [tag: ...]`
 * - `cherry-pick id: ...`
 */
class MermaidGitGraphParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0
    private var headerSeen = false
    private var title: String? = null
    private var autoCommit = 0

    private val branches: MutableList<String> = mutableListOf("main")
    private val branchHeads: LinkedHashMap<String, NodeId?> = linkedMapOf("main" to null)
    private var currentBranch: String = "main"
    private val commits: MutableList<GitCommit> = ArrayList()

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.GITGRAPH_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'gitGraph' header")
        }

        val s = toks.joinToString(" ") { it.text.toString() }.trim()
        if (s.isBlank()) return IrPatchBatch(seq, emptyList())
        if (s.startsWith("title ")) {
            title = stripQuotes(s.removePrefix("title ").trim()).ifBlank { title }
            return IrPatchBatch(seq, emptyList())
        }
        when {
            s.startsWith("branch ") -> parseBranch(s.removePrefix("branch ").trim())
            s.startsWith("checkout ") -> parseCheckout(s.removePrefix("checkout ").trim())
            s.startsWith("switch ") -> parseCheckout(s.removePrefix("switch ").trim())
            s.startsWith("commit") -> parseCommit(s.removePrefix("commit").trim())
            s.startsWith("merge ") -> parseMerge(s.removePrefix("merge ").trim())
            s.startsWith("cherry-pick ") -> parseCherryPick(s.removePrefix("cherry-pick ").trim())
            else -> diagnostics += Diagnostic(Severity.WARNING, "Unsupported gitGraph line ignored: $s", "MERMAID-W012")
        }
        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): GitGraphIR =
        GitGraphIR(
            branches = branches.toList(),
            commits = commits.toList(),
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseBranch(spec: String) {
        val attrs = parseAttrs(spec)
        val name = stripQuotes(spec.substringBefore(" order:").trim()).ifBlank { stripQuotes(spec.trim()) }
        if (name.isBlank()) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid gitGraph branch syntax", "MERMAID-E210")
            return
        }
        if (!branchHeads.containsKey(name)) {
            branches += name
            branchHeads[name] = branchHeads[currentBranch]
        }
        currentBranch = name
    }

    private fun parseCheckout(spec: String) {
        val name = stripQuotes(spec.trim())
        if (!branchHeads.containsKey(name)) {
            diagnostics += Diagnostic(Severity.ERROR, "Unknown gitGraph branch '$name'", "MERMAID-E210")
            return
        }
        currentBranch = name
    }

    private fun parseCommit(spec: String) {
        val attrs = parseAttrs(spec)
        val parent = branchHeads[currentBranch]
        val idText = attrs["id"] ?: nextCommitId()
        val commit = GitCommit(
            id = NodeId(idText),
            branch = currentBranch,
            parents = listOfNotNull(parent),
            tag = attrs["tag"],
            type = parseCommitType(attrs["type"], default = GitCommitType.Normal),
            label = RichLabel.Plain(idText),
        )
        commits += commit
        branchHeads[currentBranch] = commit.id
    }

    private fun parseMerge(spec: String) {
        val branchName = stripQuotes(spec.substringBefore(' ').trim())
        if (branchName.isBlank() || !branchHeads.containsKey(branchName) || branchName == currentBranch) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid gitGraph merge syntax", "MERMAID-E210")
            return
        }
        val sourceHead = branchHeads[branchName]
        val currentHead = branchHeads[currentBranch]
        if (sourceHead == null || currentHead == null) {
            diagnostics += Diagnostic(Severity.ERROR, "gitGraph merge requires commits on both branches", "MERMAID-E210")
            return
        }
        val attrs = parseAttrs(spec.removePrefix(branchName).trim())
        val idText = attrs["id"] ?: nextCommitId()
        val type = parseCommitType(attrs["type"], default = GitCommitType.Merge)
        val commit = GitCommit(
            id = NodeId(idText),
            branch = currentBranch,
            parents = listOf(currentHead, sourceHead),
            tag = attrs["tag"],
            type = type,
            label = RichLabel.Plain(idText),
        )
        commits += commit
        branchHeads[currentBranch] = commit.id
    }

    private fun parseCherryPick(spec: String) {
        val attrs = parseAttrs(spec)
        val sourceId = attrs["id"]
        val currentHead = branchHeads[currentBranch]
        val sourceCommit = commits.firstOrNull { it.id.value == sourceId }
        if (sourceId.isNullOrBlank() || sourceCommit == null || currentHead == null || sourceCommit.branch == currentBranch) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid gitGraph cherry-pick syntax", "MERMAID-E210")
            return
        }
        val newId = nextCommitId()
        val commit = GitCommit(
            id = NodeId(newId),
            branch = currentBranch,
            parents = listOf(currentHead),
            type = GitCommitType.CherryPick,
            label = RichLabel.Plain(sourceId),
        )
        commits += commit
        branchHeads[currentBranch] = commit.id
    }

    private fun parseAttrs(spec: String): Map<String, String> {
        if (spec.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < spec.length) {
            while (i < spec.length && spec[i].isWhitespace()) i++
            val keyStart = i
            while (i < spec.length && (spec[i].isLetterOrDigit() || spec[i] == '-' || spec[i] == '_')) i++
            if (i >= spec.length || i == keyStart || spec[i] != ':') {
                while (i < spec.length && !spec[i].isWhitespace()) i++
                continue
            }
            val key = spec.substring(keyStart, i)
            i++
            while (i < spec.length && spec[i].isWhitespace()) i++
            val value = if (i < spec.length && (spec[i] == '"' || spec[i] == '\'')) {
                val quote = spec[i++]
                val start = i
                while (i < spec.length && spec[i] != quote) i++
                val v = spec.substring(start, i.coerceAtMost(spec.length))
                if (i < spec.length && spec[i] == quote) i++
                v
            } else {
                val start = i
                while (i < spec.length && !spec[i].isWhitespace()) i++
                spec.substring(start, i)
            }
            if (value.isNotBlank()) out[key.lowercase()] = value
        }
        return out
    }

    private fun parseCommitType(raw: String?, default: GitCommitType): GitCommitType =
        when (raw?.uppercase()) {
            "REVERSE" -> GitCommitType.Reverse
            "HIGHLIGHT" -> GitCommitType.Highlight
            "MERGE" -> GitCommitType.Merge
            "CHERRY_PICK", "CHERRY-PICK" -> GitCommitType.CherryPick
            else -> default
        }

    private fun nextCommitId(): String {
        autoCommit++
        return "c$autoCommit"
    }

    private fun stripQuotes(raw: String): String =
        raw.removeSurrounding("\"").removeSurrounding("'")

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E210")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
