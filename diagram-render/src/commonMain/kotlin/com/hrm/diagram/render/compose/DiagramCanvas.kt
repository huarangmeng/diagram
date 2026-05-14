package com.hrm.diagram.render.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.hrm.diagram.core.draw.Color as DiagramColor
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Rect as DiagramRect
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlin.math.min

/**
 * Renders a [DiagramSnapshot]'s `drawCommands` into a Compose [Canvas]. Pure projection: the
 * command list is the single source of truth, so the JVM/Android/iOS/JS/Wasm targets all paint
 * identically.
 *
 * Z-ordering is honoured by stable-sorting before draw; nested [DrawCommand.Group] /
 * [DrawCommand.Clip] preserve their parent's Z slot. Text uses the multiplatform
 * [TextMeasurer]; no platform shaping API is touched.
 */
@Composable
fun DiagramCanvas(
    snapshot: DiagramSnapshot,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val sortedCommands = remember(snapshot.drawCommands) {
        snapshot.drawCommands.sortedBy { it.z }
    }
    Canvas(modifier = modifier) {
        val bounds = snapshot.laidOut?.bounds
        if (bounds == null || bounds.size.width <= 0f || bounds.size.height <= 0f) {
            for (cmd in sortedCommands) execute(cmd, measurer)
            return@Canvas
        }

        val fitScale = min(size.width / bounds.size.width, size.height / bounds.size.height)
            .coerceAtMost(1f)
        val offsetX = (size.width - bounds.size.width * fitScale) / 2f
        val offsetY = (size.height - bounds.size.height * fitScale) / 2f

        withTransform({
            translate(left = offsetX, top = offsetY)
            scale(scaleX = fitScale, scaleY = fitScale)
            translate(left = -bounds.left, top = -bounds.top)
        }) {
            for (cmd in sortedCommands) execute(cmd, measurer)
        }
    }
}

private fun DrawScope.execute(cmd: DrawCommand, measurer: TextMeasurer) {
    when (cmd) {
        is DrawCommand.FillRect -> drawDiagramRect(cmd.rect, cmd.color, cmd.corner, fill = true, strokeWidth = 0f, dash = null)
        is DrawCommand.StrokeRect -> drawDiagramRect(cmd.rect, cmd.color, cmd.corner, fill = false, strokeWidth = cmd.stroke.width, dash = cmd.stroke.dash)
        is DrawCommand.FillPath -> drawPath(path = cmd.path.toComposePath(), color = cmd.color.toCompose())
        is DrawCommand.StrokePath -> {
            val pathEffect = cmd.stroke.dash?.takeIf { it.isNotEmpty() }
                ?.let { PathEffect.dashPathEffect(it.toFloatArray(), 0f) }
            drawPath(
                path = cmd.path.toComposePath(),
                color = cmd.color.toCompose(),
                style = ComposeStroke(width = cmd.stroke.width, pathEffect = pathEffect),
            )
        }
        is DrawCommand.DrawText -> {
            val style = TextStyle(color = cmd.color.toCompose(), fontSize = cmd.font.sizeSp.sp)
            val mw = cmd.maxWidth
            val constraints = if (mw != null && mw > 0f) {
                Constraints(maxWidth = mw.toInt().coerceAtLeast(1))
            } else Constraints()
            val layout = measurer.measure(text = cmd.text, style = style, constraints = constraints)
            val w = layout.size.width.toFloat()
            val h = layout.size.height.toFloat()
            val ascent = layout.firstBaseline
            val tlx = when (cmd.anchorX) {
                TextAnchorX.Start -> cmd.origin.x
                TextAnchorX.Center -> cmd.origin.x - w / 2f
                TextAnchorX.End -> cmd.origin.x - w
            }
            val tly = when (cmd.anchorY) {
                TextAnchorY.Top -> cmd.origin.y
                TextAnchorY.Middle -> cmd.origin.y - h / 2f
                TextAnchorY.Baseline -> cmd.origin.y - ascent
                TextAnchorY.Bottom -> cmd.origin.y - h
            }
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(tlx, tly),
            )
        }
        is DrawCommand.DrawArrow -> {
            val path = Path().apply {
                moveTo(cmd.from.x, cmd.from.y)
                lineTo(cmd.to.x, cmd.to.y)
            }
            drawPath(path = path, color = ComposeColor.Black, style = ComposeStroke(width = 1.5f))
        }
        is DrawCommand.DrawIcon -> Unit
        is DrawCommand.Hyperlink -> Unit
        is DrawCommand.Group -> {
            val t = cmd.transform
            if (t.isIdentity) {
                for (c in cmd.children) execute(c, measurer)
            } else {
                withTransform({
                    translate(left = t.translate.x, top = t.translate.y)
                    rotate(degrees = t.rotateDeg)
                    scale(scaleX = t.scale, scaleY = t.scale)
                }) {
                    for (c in cmd.children) execute(c, measurer)
                }
            }
        }
        is DrawCommand.Clip -> {
            clipRect(
                left = cmd.rect.left, top = cmd.rect.top,
                right = cmd.rect.right, bottom = cmd.rect.bottom,
            ) {
                for (c in cmd.children) execute(c, measurer)
            }
        }
    }
}

private fun DrawScope.drawDiagramRect(
    rect: DiagramRect,
    color: DiagramColor,
    corner: Float,
    fill: Boolean,
    strokeWidth: Float,
    dash: List<Float>? = null,
) {
    val topLeft = Offset(rect.left, rect.top)
    val size = Size(rect.size.width, rect.size.height)
    val pathEffect = dash?.takeIf { it.isNotEmpty() }?.let { PathEffect.dashPathEffect(it.toFloatArray(), 0f) }
    if (corner > 0f) {
        if (fill) drawRoundRect(color = color.toCompose(), topLeft = topLeft, size = size, cornerRadius = CornerRadius(corner, corner))
        else drawRoundRect(color = color.toCompose(), topLeft = topLeft, size = size, cornerRadius = CornerRadius(corner, corner), style = ComposeStroke(width = strokeWidth, pathEffect = pathEffect))
    } else {
        if (fill) drawRect(color = color.toCompose(), topLeft = topLeft, size = size)
        else drawRect(color = color.toCompose(), topLeft = topLeft, size = size, style = ComposeStroke(width = strokeWidth, pathEffect = pathEffect))
    }
}

private fun DiagramColor.toCompose(): ComposeColor = ComposeColor(argb)

private fun PathCmd.toComposePath(): Path {
    val path = Path()
    for (op in ops) when (op) {
        is PathOp.MoveTo -> path.moveTo(op.p.x, op.p.y)
        is PathOp.LineTo -> path.lineTo(op.p.x, op.p.y)
        is PathOp.QuadTo -> path.quadraticTo(op.ctrl.x, op.ctrl.y, op.end.x, op.end.y)
        is PathOp.CubicTo -> path.cubicTo(op.c1.x, op.c1.y, op.c2.x, op.c2.y, op.end.x, op.end.y)
        PathOp.Close -> path.close()
    }
    return path
}
