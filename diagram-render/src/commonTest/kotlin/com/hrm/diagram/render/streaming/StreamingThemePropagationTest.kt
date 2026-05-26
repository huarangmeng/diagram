package com.hrm.diagram.render.streaming

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.theme.DiagramTheme
import com.hrm.diagram.core.theme.GraphColors
import com.hrm.diagram.core.theme.SequenceColors
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertTrue

class StreamingThemePropagationTest {
    @Test
    fun mermaidFlowchart_uses_theme_resolver_defaults() {
        val nodeFill = Color(0xFFE8FFF1.toInt())
        val nodeStroke = Color(0xFF00875A.toInt())
        val nodeText = Color(0xFF005A32.toInt())
        val edgeColor = Color(0xFF7A4D00.toInt())
        val edgeLabelText = Color(0xFF6B1FA1.toInt())
        val edgeLabelBg = Color(0xFFFFF1D6.toInt())
        val graphBackground = Color(0xFFF9FFFC.toInt())
        val theme = DiagramTheme.Default.copy(
            graphColors = GraphColors(
                background = graphBackground,
                nodeFill = nodeFill,
                nodeStroke = nodeStroke,
                nodeText = nodeText,
                edge = edgeColor,
                edgeLabelText = edgeLabelText,
                edgeLabelBackground = edgeLabelBg,
            ),
        )

        val snapshot = runSession(
            language = SourceLanguage.MERMAID,
            theme = theme,
            source = "flowchart TD\nA[Alpha] -->|hello| B[Beta]\n",
        )

        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.color.argb == graphBackground.argb })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.color.argb == nodeFill.argb })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().any { it.color.argb == nodeStroke.argb })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.color.argb == edgeColor.argb })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.color.argb == nodeText.argb })
        assertTrue(snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.color.argb == edgeLabelText.argb })
    }

    @Test
    fun plantUmlSequence_uses_theme_resolver_defaults() {
        val headerFill = Color(0xFFE8F0FF.toInt())
        val headerStroke = Color(0xFF335CFF.toInt())
        val headerText = Color(0xFF173199.toInt())
        val messageColor = Color(0xFF7A3E00.toInt())
        val noteFill = Color(0xFFFFF1C1.toInt())
        val noteStroke = Color(0xFFFF8B00.toInt())
        val activationFill = Color(0xFFF7FBFF.toInt())
        val activationStroke = Color(0xFF005CC5.toInt())
        val theme = DiagramTheme.Default.copy(
            sequenceColors = SequenceColors(
                headerFill = headerFill,
                headerStroke = headerStroke,
                headerText = headerText,
                message = messageColor,
                messageText = messageColor,
                noteFill = noteFill,
                noteStroke = noteStroke,
                noteText = noteStroke,
                activationFill = activationFill,
                activationStroke = activationStroke,
            ),
        )

        val snapshot = runSession(
            language = SourceLanguage.PLANTUML,
            theme = theme,
            source = """
                @startuml
                skinparam shadowing true
                participant Alice
                participant Bob
                Alice -> Bob: hello
                activate Bob
                note right of Bob: memo
                @enduml
            """.trimIndent() + "\n",
        )

        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        val strokes = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokeRect>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        val paths = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokePath>()
        assertTrue(fills.any { it.color.argb == headerFill.argb })
        assertTrue(strokes.any { it.color.argb == headerStroke.argb })
        assertTrue(texts.any { it.color.argb == headerText.argb })
        assertTrue(paths.any { it.color.argb == messageColor.argb })
        assertTrue(fills.any { it.color.argb == noteFill.argb })
        assertTrue(strokes.any { it.color.argb == noteStroke.argb })
        assertTrue(fills.any { it.color.argb == activationFill.argb })
        assertTrue(strokes.any { it.color.argb == activationStroke.argb })
    }

    @Test
    fun plantUmlComponent_uses_theme_resolver_defaults() {
        val noteFill = Color(0xFFFFF8E1.toInt())
        val border = Color(0xFF7A52CC.toInt())
        val colors = DiagramTheme.Default.colors.copy(
            canvas = Color(0xFFFCFFFD.toInt()),
            textPrimary = Color(0xFF16324F.toInt()),
            textSecondary = Color(0xFF4A6572.toInt()),
            accentSecondary = Color(0xFF8E24AA.toInt()),
            border = border,
        )
        val theme = DiagramTheme.Default.copy(
            colors = colors,
            graphColors = DiagramTheme.Default.graphColors.copy(
                noteFill = noteFill,
                noteStroke = Color(0xFFFFB300.toInt()),
            ),
        )

        val snapshot = runSession(
            language = SourceLanguage.PLANTUML,
            theme = theme,
            source = """
                @startuml
                skinparam component {
                  Shadowing true
                }
                skinparam note {
                  Shadowing true
                }
                skinparam package {
                  Shadowing true
                }
                package "Backend" {
                  [API] --> [DB] : calls
                }
                note right of API: memo
                @enduml
            """.trimIndent() + "\n",
        )

        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        val shadowTint = Color.argb(56, border.r, border.g, border.b)
        assertTrue(fills.any { it.color.argb == colors.canvas.argb })
        assertTrue(fills.any { it.color.argb == noteFill.argb })
        assertTrue(fills.any { it.color.argb == shadowTint.argb })
        assertTrue(texts.any { it.color.argb == colors.textPrimary.argb })
    }

    @Test
    fun plantUmlNetwork_uses_theme_resolver_defaults() {
        val canvas = Color(0xFFF7FBFF.toInt())
        val textPrimary = Color(0xFF17324D.toInt())
        val theme = DiagramTheme.Default.copy(
            colors = DiagramTheme.Default.colors.copy(
                canvas = canvas,
                textPrimary = textPrimary,
                accentSecondary = Color(0xFF7B1FA2.toInt()),
            ),
        )

        val snapshot = runSession(
            language = SourceLanguage.PLANTUML,
            theme = theme,
            source = """
                @startnwdiag
                nwdiag {
                  network dmz {
                    web
                    db
                    web -- db
                  }
                }
                @endnwdiag
            """.trimIndent() + "\n",
        )

        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(fills.any { it.color.argb == canvas.argb })
        assertTrue(texts.any { it.color.argb == textPrimary.argb })
    }

    @Test
    fun mermaidGitGraph_uses_theme_resolver_defaults() {
        val canvas = Color(0xFFF6FBFF.toInt())
        val lane = Color(0xFFB7C6D8.toInt())
        val textPrimary = Color(0xFF18324A.toInt())
        val theme = DiagramTheme.Default.copy(
            colors = DiagramTheme.Default.colors.copy(
                canvas = canvas,
                border = lane,
                textPrimary = textPrimary,
            ),
        )

        val snapshot = runSession(
            language = SourceLanguage.MERMAID,
            theme = theme,
            source = """
                gitGraph
                  commit id: "a"
                  branch feature
                  checkout feature
                  commit id: "b"
            """.trimIndent() + "\n",
        )

        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        val paths = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokePath>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(fills.any { it.color.argb == canvas.argb })
        assertTrue(paths.any { it.color.argb == lane.argb })
        assertTrue(texts.any { it.color.argb == textPrimary.argb })
    }

    @Test
    fun dot_uses_theme_resolver_defaults() {
        val background = Color(0xFFF4FBFF.toInt())
        val nodeFill = Color(0xFFE9F6FF.toInt())
        val nodeStroke = Color(0xFF2A6F97.toInt())
        val nodeText = Color(0xFF14324B.toInt())
        val edgeColor = Color(0xFF8C5A00.toInt())
        val edgeLabelText = Color(0xFF6D28D9.toInt())
        val clusterFill = Color(0xFFF7F2FF.toInt())
        val clusterStroke = Color(0xFF7E57C2.toInt())
        val theme = DiagramTheme.Default.copy(
            graphColors = GraphColors(
                background = background,
                nodeFill = nodeFill,
                nodeStroke = nodeStroke,
                nodeText = nodeText,
                edge = edgeColor,
                edgeLabelText = edgeLabelText,
                clusterFill = clusterFill,
                clusterStroke = clusterStroke,
            ),
        )

        val snapshot = runSession(
            language = SourceLanguage.DOT,
            theme = theme,
            source = """
                digraph {
                  subgraph cluster_api {
                    label="API"
                    a
                  }
                  a -> b [label="edge"]
                }
            """.trimIndent() + "\n",
        )

        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        val strokeRects = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokeRect>()
        val strokePaths = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokePath>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(fills.any { it.color.argb == background.argb })
        assertTrue(fills.any { it.color.argb == nodeFill.argb })
        assertTrue(fills.any { it.color.argb == clusterFill.argb })
        assertTrue(strokeRects.any { it.color.argb == nodeStroke.argb })
        assertTrue(strokeRects.any { it.color.argb == clusterStroke.argb })
        assertTrue(strokePaths.any { it.color.argb == edgeColor.argb })
        assertTrue(texts.any { it.text == "a" && it.color.argb == nodeText.argb })
        assertTrue(texts.any { it.text == "edge" && it.color.argb == edgeLabelText.argb })
    }

    @Test
    fun plantUmlSalt_uses_theme_resolver_defaults() {
        val surfaceAlt = Color(0xFFF2F7FD.toInt())
        val border = Color(0xFFA0B4C8.toInt())
        val textPrimary = Color(0xFF1B2B40.toInt())
        val theme = DiagramTheme.Default.copy(
            colors = DiagramTheme.Default.colors.copy(
                surfaceAlt = surfaceAlt,
                border = border,
                textPrimary = textPrimary,
            ),
        )

        val snapshot = runSession(
            language = SourceLanguage.PLANTUML,
            theme = theme,
            source = """
                @startsalt
                {
                  Login | "user"
                }
                @endsalt
            """.trimIndent() + "\n",
        )

        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        val strokes = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokeRect>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(fills.any { it.color.argb == surfaceAlt.argb })
        assertTrue(strokes.any { it.color.argb == border.argb })
        assertTrue(texts.any { it.color.argb == textPrimary.argb })
    }

    private fun runSession(
        language: SourceLanguage,
        theme: DiagramTheme,
        source: String,
    ): DiagramSnapshot = Diagram.session(language = language, theme = theme).let { session ->
        try {
            session.append(source)
            session.finish()
        } finally {
            session.close()
        }
    }
}
