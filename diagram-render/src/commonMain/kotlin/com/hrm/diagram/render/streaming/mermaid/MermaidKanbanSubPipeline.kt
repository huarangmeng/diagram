package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.KanbanIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.kanban.KanbanLayout
import com.hrm.diagram.parser.mermaid.MermaidKanbanParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch

internal class MermaidKanbanSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {
    private var styleExtras: Map<String, String> = emptyMap()

    override fun updateStyleExtras(extras: Map<String, String>) {
        styleExtras = extras
    }

    private val parser = MermaidKanbanParser()
    private val layout = KanbanLayout(textMeasurer)

    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val headerFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val cardFont = FontSpec(family = "sans-serif", sizeSp = 12f)
    private val metaFont = FontSpec(family = "sans-serif", sizeSp = 10f)

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val newPatches = ArrayList<IrPatch>()
        for (line in lines) {
            val batch = parser.acceptLine(line)
            newPatches += batch.patches
        }
        val ir = parser.snapshot()
        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val draw = render(ir, laid)
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }

        val snap = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = draw,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        val patch = SessionPatch(
            seq = seq,
            addedNodes = emptyList(),
            addedEdges = emptyList(),
            addedDrawCommands = draw,
            newDiagnostics = newDiagnostics,
            isFinal = isFinal,
        )
        return PipelineAdvance(snapshot = snap, patch = patch)
    }

    private fun render(ir: KanbanIR, laid: com.hrm.diagram.layout.LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laid.bounds
        val bg = Color(0xFFF7F9FC.toInt())
        val colFill = Color(0xFFECEFF1.toInt())
        val colStroke = Color(0xFFB0BEC5.toInt())
        val headerFill = Color(0xFFCFD8DC.toInt())
        val cardFill = Color(0xFFFFFFFF.toInt())
        val cardStroke = Color(0xFFB0BEC5.toInt())
        val text = Color(0xFF263238.toInt())
        val metaColor = Color(0xFF607D8B.toInt())
        val ticketBaseUrl = styleExtras["mermaid.config.kanban.ticketBaseUrl"]

        out += DrawCommand.FillRect(rect = Rect(Point(0f, 0f), Size(bounds.size.width, bounds.size.height)), color = bg, corner = 0f, z = 0)

        val titleRect = laid.nodePositions[NodeId("kanban:title")]
        if (titleRect != null && !ir.title.isNullOrBlank()) {
            out += DrawCommand.DrawText(
                text = ir.title!!,
                origin = Point(titleRect.left, titleRect.top),
                font = titleFont,
                color = text,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Top,
                z = 10,
            )
        }

        for (col in ir.columns) {
            val colRect = laid.nodePositions[NodeId("kanban:column:${col.id.value}")] ?: continue
            val headerRect = laid.nodePositions[NodeId("kanban:columnHeader:${col.id.value}")] ?: continue

            out += DrawCommand.FillRect(rect = colRect, color = colFill, corner = 10f, z = 1)
            out += DrawCommand.StrokeRect(rect = colRect, stroke = Stroke(width = 1f), color = colStroke, corner = 10f, z = 2)
            out += DrawCommand.FillRect(rect = headerRect, color = headerFill, corner = 10f, z = 3)

            val headerText = (col.label as? RichLabel.Plain)?.text ?: col.id.value
            out += DrawCommand.DrawText(
                text = headerText,
                origin = Point((headerRect.left + headerRect.right) / 2f, (headerRect.top + headerRect.bottom) / 2f),
                font = headerFont,
                color = text,
                anchorX = TextAnchorX.Center,
                anchorY = TextAnchorY.Middle,
                z = 4,
            )

            for (card in col.cards) {
                val cardRect = laid.nodePositions[NodeId("kanban:card:${card.id.value}")] ?: continue
                out += DrawCommand.FillRect(rect = cardRect, color = cardFill, corner = 8f, z = 5)
                out += DrawCommand.StrokeRect(rect = cardRect, stroke = Stroke(width = 1f), color = cardStroke, corner = 8f, z = 6)

                val label = (card.label as? RichLabel.Plain)?.text ?: card.id.value
                out += DrawCommand.DrawText(
                    text = label,
                    origin = Point(cardRect.left + 10f, cardRect.top + 10f),
                    font = cardFont,
                    color = text,
                    anchorX = TextAnchorX.Start,
                    anchorY = TextAnchorY.Top,
                    maxWidth = cardRect.size.width - 20f,
                    z = 7,
                )

                card.payload["priority"]?.let { priority ->
                    val badgeText = priority
                    val badgeFill = when (priority.lowercase()) {
                        "very high" -> Color(0xFFD32F2F.toInt())
                        "high" -> Color(0xFFF57C00.toInt())
                        "low" -> Color(0xFF388E3C.toInt())
                        "very low" -> Color(0xFF1976D2.toInt())
                        else -> Color(0xFF78909C.toInt())
                    }
                    val badgeRect = Rect.ltrb(cardRect.right - 82f, cardRect.top + 8f, cardRect.right - 8f, cardRect.top + 26f)
                    out += DrawCommand.FillRect(rect = badgeRect, color = badgeFill, corner = 9f, z = 7)
                    out += DrawCommand.DrawText(
                        text = badgeText,
                        origin = Point((badgeRect.left + badgeRect.right) / 2f, (badgeRect.top + badgeRect.bottom) / 2f),
                        font = metaFont,
                        color = Color.White,
                        anchorX = TextAnchorX.Center,
                        anchorY = TextAnchorY.Middle,
                        maxWidth = badgeRect.size.width - 8f,
                        z = 8,
                    )
                }

                val metaLines = buildMetaLines(card.payload)
                var metaY = cardRect.bottom - 10f
                for (i in metaLines.indices.reversed()) {
                    val meta = metaLines[i]
                    out += DrawCommand.DrawText(
                        text = meta,
                        origin = Point(cardRect.left + 10f, metaY),
                        font = metaFont,
                        color = metaColor,
                        anchorX = TextAnchorX.Start,
                        anchorY = TextAnchorY.Bottom,
                        maxWidth = cardRect.size.width - 20f,
                        z = 7,
                    )
                    if (ticketBaseUrl != null && card.payload["ticket"] == meta) {
                        val href = ticketBaseUrl.replace("#TICKET#", meta)
                        val m = textMeasurer.measure(meta, metaFont, maxWidth = cardRect.size.width - 20f)
                        val linkRect = Rect.ltrb(cardRect.left + 10f, metaY - m.height, cardRect.left + 10f + m.width, metaY)
                        out += DrawCommand.Hyperlink(href = href, rect = linkRect, z = 9)
                    }
                    val m = textMeasurer.measure(meta, metaFont, maxWidth = cardRect.size.width - 20f)
                    metaY -= m.height + 4f
                }
            }
        }
        return out
    }

    private fun buildMetaLines(payload: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        payload["ticket"]?.let { out += it }
        payload["assigned"]?.let { out += it }
        payload["priority"]?.let { out += it }
        return out
    }
}
