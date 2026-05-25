package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.streaming.Token

@DiagramApi
interface MermaidLexingSession {
    fun feed(
        chunk: CharSequence,
        absoluteOffset: Int,
        eos: Boolean = false,
    ): List<Token>
}

@DiagramApi
object MermaidFrontend {
    @DiagramApi
    fun createLexingSession(): MermaidLexingSession = DefaultMermaidLexingSession()

    @DiagramApi
    fun isNewlineToken(token: Token): Boolean = token.kind == MermaidTokenKind.NEWLINE

    @DiagramApi
    fun isCommentToken(token: Token): Boolean = token.kind == MermaidTokenKind.COMMENT

    @DiagramApi
    fun parseFrontmatterThemeConfig(frontmatter: String): MermaidStyleParsers.ParseThemeConfigResult? =
        MermaidStyleParsers.parseFrontmatterThemeConfig(frontmatter)

    @DiagramApi
    fun parseClassDefLine(line: String): MermaidStyleParsers.ParseClassDefResult? =
        MermaidStyleParsers.parseClassDefLine(line)

    @DiagramApi
    fun parseClassAssignLine(line: String): MermaidStyleParsers.ParseClassAssignResult? =
        MermaidStyleParsers.parseClassAssignLine(line)

    @DiagramApi
    fun parseNodeStyleLine(line: String): MermaidStyleParsers.ParseNodeStyleResult? =
        MermaidStyleParsers.parseNodeStyleLine(line)

    @DiagramApi
    fun parseLinkStyleLine(line: String): MermaidStyleParsers.ParseLinkStyleResult? =
        MermaidStyleParsers.parseLinkStyleLine(line)

    @DiagramApi
    fun encodeThemeTokens(tokens: MermaidThemeTokens): String =
        MermaidStyleExtrasCodec.encodeThemeTokens(tokens)

    @DiagramApi
    fun encodeClassDefs(classDefs: Map<String, MermaidStyleDecl>): String =
        MermaidStyleExtrasCodec.encodeClassDefs(classDefs)
}

private class DefaultMermaidLexingSession : MermaidLexingSession {
    private val lexer = MermaidLexer()
    private var state: MermaidLexerState = lexer.initialState()

    override fun feed(
        chunk: CharSequence,
        absoluteOffset: Int,
        eos: Boolean,
    ): List<Token> {
        val step = lexer.feed(
            state = state,
            input = chunk,
            offset = absoluteOffset,
            eos = eos,
        )
        state = step.newState
        return step.tokens
    }
}
