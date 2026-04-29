package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.GitCommitType
import com.hrm.diagram.core.ir.GitGraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.gitgraph.GitGraphLayout
import com.hrm.diagram.parser.mermaid.MermaidGitGraphParser
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch

internal class MermaidGitGraphSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {
    private val parser = MermaidGitGraphParser()
    private val layout = GitGraphLayout(textMeasurer)
    private val titleFont = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600)
    private val branchFont = FontSpec(family = "sans-serif", sizeSp = 12f, weight = 600)
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 10f)
    private val tagFont = FontSpec(family = "sans-serif", sizeSp = 10f, weight = 600)

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        for (line in lines) parser.acceptLine(line)
        val ir = parser.snapshot()
        val laid = layout.layout(
            previous = previousSnapshot.laidOut,
            model = ir,
            options = LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal),
        )
        val draw = render(ir, laid)
        val snap = DiagramSnapshot(
            ir = ir,
            laidOut = laid,
            drawCommands = draw,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        return PipelineAdvance(snapshot = snap, patch = SessionPatch.empty(seq, isFinal))
    }

    private fun render(ir: GitGraphIR, laid: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>()
        val bounds = laid.bounds
        val text = Color(0xFF263238.toInt())
        val lane = Color(0xFFE0E0E0.toInt())
        val border = Color(0xFF455A64.toInt())
        val branchColors = ir.branches.mapIndexed { index, name -> name to palette(index) }.toMap()

        out += DrawCommand.FillRect(Rect(Point(0f, 0f), Size(bounds.size.width, bounds.size.height)), Color(0xFFFFFFFF.toInt()), z = 0)
        val titleRect = laid.nodePositions[NodeId("gitgraph:title")]
        if (titleRect != null && !ir.title.isNullOrBlank()) {
            out += DrawCommand.DrawText(ir.title!!, Point(titleRect.left, titleRect.top), titleFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
        }

        for (branch in ir.branches) {
            val rect = laid.nodePositions[NodeId("gitgraph:branch:$branch")] ?: continue
            val y = rect.top + rect.size.height / 2f
            out += DrawCommand.StrokePath(
                path = PathCmd(listOf(PathOp.MoveTo(Point(90f, y)), PathOp.LineTo(Point(bounds.right - 20f, y)))),
                stroke = Stroke(width = 1f),
                color = lane,
                z = 1,
            )
            out += DrawCommand.DrawText(branch, Point(rect.left, rect.top), branchFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
        }

        for (route in laid.edgeRoutes) {
            val child = ir.commits.firstOrNull { it.id == route.to } ?: continue
            val color = branchColors[child.branch] ?: Color(0xFF90A4AE.toInt())
            out += DrawCommand.StrokePath(
                path = PathCmd(route.points.mapIndexed { index, point ->
                    if (index == 0) PathOp.MoveTo(point) else PathOp.LineTo(point)
                }),
                stroke = Stroke(width = if (child.parents.size > 1) 2f else 1.5f),
                color = color,
                z = 2,
            )
        }

        for (commit in ir.commits) {
            val rect = laid.nodePositions[NodeId("gitgraph:commit:${commit.id.value}")] ?: continue
            val fill = branchColors[commit.branch] ?: Color(0xFF78909C.toInt())
            when (commit.type) {
                GitCommitType.Highlight -> {
                    out += DrawCommand.FillRect(rect, fill, corner = 4f, z = 4)
                    out += DrawCommand.StrokeRect(rect, Stroke(width = 1f), border, corner = 4f, z = 5)
                }
                GitCommitType.Reverse -> {
                    out += DrawCommand.StrokeRect(rect, Stroke(width = 2f), fill, corner = rect.size.width / 2f, z = 5)
                    out += DrawCommand.StrokePath(
                        path = PathCmd(
                            listOf(
                                PathOp.MoveTo(Point(rect.left + 3f, rect.top + 3f)),
                                PathOp.LineTo(Point(rect.right - 3f, rect.bottom - 3f)),
                                PathOp.MoveTo(Point(rect.left + 3f, rect.bottom - 3f)),
                                PathOp.LineTo(Point(rect.right - 3f, rect.top + 3f)),
                            ),
                        ),
                        stroke = Stroke(width = 1.5f),
                        color = fill,
                        z = 6,
                    )
                }
                GitCommitType.Merge -> {
                    out += DrawCommand.FillRect(rect, fill, corner = rect.size.width / 2f, z = 4)
                    out += DrawCommand.StrokeRect(rect, Stroke(width = 2f), border, corner = rect.size.width / 2f, z = 5)
                    val inner = Rect.ltrb(rect.left + 4f, rect.top + 4f, rect.right - 4f, rect.bottom - 4f)
                    out += DrawCommand.StrokeRect(inner, Stroke(width = 1f), Color(0xFFFFFFFF.toInt()), corner = inner.size.width / 2f, z = 6)
                }
                GitCommitType.CherryPick -> {
                    out += DrawCommand.FillRect(rect, fill, corner = rect.size.width / 2f, z = 4)
                    out += DrawCommand.StrokeRect(rect, Stroke(width = 1f), border, corner = rect.size.width / 2f, z = 5)
                    val source = (commit.label as? RichLabel.Plain)?.text.orEmpty()
                    if (source.isNotBlank()) {
                        out += DrawCommand.DrawText("pick:$source", Point((rect.left + rect.right) / 2f, rect.bottom + 10f), labelFont, text, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Top, z = 10)
                    }
                }
                else -> {
                    out += DrawCommand.FillRect(rect, fill, corner = rect.size.width / 2f, z = 4)
                    out += DrawCommand.StrokeRect(rect, Stroke(width = 1f), border, corner = rect.size.width / 2f, z = 5)
                }
            }

            val labelRect = laid.nodePositions[NodeId("gitgraph:label:${commit.id.value}")]
            val label = (commit.label as? RichLabel.Plain)?.text.orEmpty()
            if (labelRect != null && label.isNotBlank()) {
                out += DrawCommand.DrawText(label, Point(labelRect.left, labelRect.top), labelFont, text, anchorX = TextAnchorX.Start, anchorY = TextAnchorY.Top, z = 10)
            }
            val tagRect = laid.nodePositions[NodeId("gitgraph:tag:${commit.id.value}")]
            if (tagRect != null && !commit.tag.isNullOrBlank()) {
                out += DrawCommand.FillRect(tagRect, Color(0xFFFFF59D.toInt()), corner = 10f, z = 7)
                out += DrawCommand.StrokeRect(tagRect, Stroke(width = 1f), border, corner = 10f, z = 8)
                out += DrawCommand.DrawText(commit.tag!!, Point((tagRect.left + tagRect.right) / 2f, tagRect.top + 3f), tagFont, text, anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Top, z = 10)
            }
        }
        return out
    }

    private fun palette(index: Int): Color = listOf(
        Color(0xFF42A5F5.toInt()),
        Color(0xFF66BB6A.toInt()),
        Color(0xFFFF7043.toInt()),
        Color(0xFFAB47BC.toInt()),
        Color(0xFF26C6DA.toInt()),
        Color(0xFFFFCA28.toInt()),
        Color(0xFF8D6E63.toInt()),
        Color(0xFFEC407A.toInt()),
    )[index % 8]
}
