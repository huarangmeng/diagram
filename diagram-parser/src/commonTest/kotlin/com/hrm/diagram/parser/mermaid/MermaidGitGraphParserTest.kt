package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.GitCommitType
import com.hrm.diagram.core.ir.GitGraphIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidGitGraphParserTest {
    private fun feedAll(src: String): MermaidGitGraphParser {
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) {
                    lines += cur
                    cur = ArrayList()
                }
            } else cur.add(t)
        }
        if (cur.isNotEmpty()) lines += cur
        return MermaidGitGraphParser().also { parser ->
            for (line in lines) parser.acceptLine(line)
        }
    }

    @Test
    fun parses_commit_branch_merge_and_cherry_pick() {
        val parser = feedAll(
            """
            gitGraph
              commit id: "A"
              branch develop
              commit id: "B" type: HIGHLIGHT
              checkout main
              commit id: "C"
              merge develop id: "M1" tag: "v1"
              branch release
              commit id: "R1"
              checkout main
              cherry-pick id: "R1"
            """.trimIndent() + "\n",
        )
        val ir = assertIs<GitGraphIR>(parser.snapshot())
        assertEquals(listOf("main", "develop", "release"), ir.branches)
        assertEquals(6, ir.commits.size)
        assertEquals(GitCommitType.Highlight, ir.commits[1].type)
        assertEquals(GitCommitType.Merge, ir.commits[3].type)
        assertEquals("v1", ir.commits[3].tag)
        assertEquals(GitCommitType.CherryPick, ir.commits.last().type)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }
}
