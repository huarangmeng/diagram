package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidC4IntegrationTest {
    @Test
    fun c4_context_renders_and_is_streaming_consistent() {
        val src =
            """
            C4Context
              title System Context
              Person(customer, "Customer", "bank user")
              Enterprise_Boundary(bank, "Bank") {
                System(core, "Core Banking", "Handles accounts")
                SystemDb(db, "Accounts DB", "Stores accounts")
              }
              System_Ext(mail, "Mail", "Sends emails")
              Rel(customer, core, "Uses")
              Rel(core, db, "Reads", "JDBC")
              BiRel(core, mail, "Sends e-mails", "SMTP")
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(1, oneIr.clusters.size)
        assertTrue(one.drawCommands.any { it is DrawCommand.StrokePath })
        assertTrue(one.drawCommands.any { it is DrawCommand.FillRect })
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
    }

    @Test
    fun c4_styles_affect_nodes_and_relationship_labels() {
        val src =
            """
            C4Container
              Person(user, "User", "bank customer")
              Container(web, "Web App", "Spring MVC", "Delivers UI")
              ContainerDb(db, "Database", "PostgreSQL", "Stores data")
              Rel(user, web, "Uses", "HTTPS")
              Rel(web, db, "Reads from", "JDBC")
              UpdateElementStyle(web, ${'$'}bgColor="#f96", ${'$'}fontColor="#123456", ${'$'}borderColor="#333333")
              UpdateRelStyle(user, web, ${'$'}textColor="red", ${'$'}lineColor="#111111", ${'$'}offsetX="20", ${'$'}offsetY="-10")
            """.trimIndent() + "\n"

        val snapshot = run(src, chunkSize = src.length)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }
        assertTrue(fills.contains(0xFFFF9966.toInt()))
        val strokes = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().map { it.color.argb }
        assertTrue(strokes.contains(0xFF333333.toInt()))
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.color.argb }
        assertTrue(texts.contains(0xFF123456.toInt()) || texts.contains(0xFFFF0000.toInt()))
    }

    @Test
    fun c4_links_and_legend_emit_draw_commands() {
        val src =
            """
            C4Container
              AddElementTag("v1.0", ${'$'}bgColor="#FFF3E0", ${'$'}borderColor="#FB8C00", ${'$'}fontColor="#E65100", ${'$'}legendText="Version 1.0")
              AddRelTag("async", ${'$'}textColor="#6A1B9A", ${'$'}lineColor="#8E24AA", ${'$'}legendText="Async Link")
              Person(user, "User", "bank customer", ${'$'}tags="v1.0", ${'$'}link="https://example.com/user")
              Container(api, "API", "Ktor", "Backend", ${'$'}tags="v1.0")
              Rel(user, api, "Uses", "HTTPS", ${'$'}tags="async", ${'$'}link="https://example.com/rel")
            """.trimIndent() + "\n"

        val snapshot = run(src, chunkSize = src.length)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        val links = snapshot.drawCommands.filterIsInstance<DrawCommand.Hyperlink>().map { it.href }
        assertTrue(links.contains("https://example.com/user"))
        assertTrue(links.contains("https://example.com/rel"))
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.text }
        assertTrue(texts.contains("Legend"))
        assertTrue(texts.contains("Version 1.0"))
        assertTrue(texts.contains("Async Link"))
    }

    @Test
    fun c4_helper_shapes_and_line_styles_render() {
        val src =
            """
            C4Container
              AddElementTag("rounded", ${'$'}shape="RoundedBoxShape()", ${'$'}legendText="Rounded")
              AddElementTag("octa", ${'$'}shape="EightSidedShape()")
              AddRelTag("dashed", ${'$'}lineStyle="DashedLine()", ${'$'}legendText="Dashed")
              Person(user, "User", "bank customer", ${'$'}tags="rounded")
              Container(api, "API", "Ktor", "Backend", ${'$'}tags="octa")
              Rel(user, api, "Uses", "HTTPS", ${'$'}tags="dashed")
            """.trimIndent() + "\n"

        val snapshot = run(src, chunkSize = src.length)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        val strokePaths = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokePath>()
        assertTrue(strokePaths.any { it.stroke.dash == listOf(8f, 6f) })
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.text }
        assertTrue(texts.contains("Dashed"))
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(language = SourceLanguage.MERMAID).let { s ->
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                s.append(src.substring(i, end))
                i = end
            }
            s.finish()
        } finally {
            s.close()
        }
    }

    private fun drawSignature(cmds: List<DrawCommand>): List<DrawSig> = cmds.map { it.toSig() }

    private sealed interface DrawSig {
        val z: Int
        data class FillRectSig(val colorArgb: Int, val corner: Float, override val z: Int) : DrawSig
        data class StrokeRectSig(val colorArgb: Int, val stroke: StrokeSig, val corner: Float, override val z: Int) : DrawSig
        data class FillPathSig(val colorArgb: Int, override val z: Int) : DrawSig
        data class StrokePathSig(val colorArgb: Int, val stroke: StrokeSig, override val z: Int) : DrawSig
        data class DrawTextSig(val text: String, val font: FontSig, val colorArgb: Int, val maxWidth: Float?, val anchorX: TextAnchorX, val anchorY: TextAnchorY, override val z: Int) : DrawSig
        data class HyperlinkSig(val href: String, override val z: Int) : DrawSig
    }

    private data class StrokeSig(val width: Float, val dash: List<Float>?)
    private data class FontSig(val family: String, val sizeSp: Float, val weight: Int, val italic: Boolean)

    private fun Stroke.toSig(): StrokeSig = StrokeSig(width = width, dash = dash)
    private fun FontSpec.toSig(): FontSig = FontSig(family = family, sizeSp = sizeSp, weight = weight, italic = italic)

    private fun DrawCommand.toSig(): DrawSig =
        when (this) {
            is DrawCommand.FillRect -> DrawSig.FillRectSig(colorArgb = color.argb, corner = corner, z = z)
            is DrawCommand.StrokeRect -> DrawSig.StrokeRectSig(colorArgb = color.argb, stroke = stroke.toSig(), corner = corner, z = z)
            is DrawCommand.FillPath -> DrawSig.FillPathSig(colorArgb = color.argb, z = z)
            is DrawCommand.StrokePath -> DrawSig.StrokePathSig(colorArgb = color.argb, stroke = stroke.toSig(), z = z)
            is DrawCommand.DrawText -> DrawSig.DrawTextSig(text, font.toSig(), color.argb, maxWidth, anchorX, anchorY, z)
            is DrawCommand.Hyperlink -> DrawSig.HyperlinkSig(href, z)
            else -> DrawSig.DrawTextSig(this::class.simpleName ?: "Unknown", FontSig("", 0f, 0, false), 0, null, TextAnchorX.Start, TextAnchorY.Top, z)
        }
}
