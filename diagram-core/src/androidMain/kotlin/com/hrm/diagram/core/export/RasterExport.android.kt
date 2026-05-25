package com.hrm.diagram.core.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.hrm.diagram.core.draw.ArrowHead
import com.hrm.diagram.core.draw.Cap
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.Join
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.draw.Transform
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

actual suspend fun RenderedDiagram.exportPng(
    options: RasterExportOptions,
): ExportArtifact<ByteArray> {
    val plan = planRasterExport(
        scale = options.scale,
        background = options.background,
        opaqueRequired = false,
    )
    val render = renderRasterBitmap(plan = plan, opaque = false)
    return ExportArtifact(
        value = encodeBitmap(render.bitmap, Bitmap.CompressFormat.PNG, 100),
        mimeType = "image/png",
        widthPx = plan.widthPx,
        heightPx = plan.heightPx,
        diagnostics = plan.diagnostics + render.diagnostics,
    )
}

actual suspend fun RenderedDiagram.exportJpeg(
    options: JpegExportOptions,
): ExportArtifact<ByteArray> {
    val plan = planRasterExport(
        scale = options.scale,
        background = options.background,
        opaqueRequired = true,
    )
    val render = renderRasterBitmap(plan = plan, opaque = true)
    return ExportArtifact(
        value = encodeBitmap(render.bitmap, Bitmap.CompressFormat.JPEG, options.quality),
        mimeType = "image/jpeg",
        widthPx = plan.widthPx,
        heightPx = plan.heightPx,
        diagnostics = plan.diagnostics + render.diagnostics,
    )
}

private data class AndroidRasterRenderResult(
    val bitmap: Bitmap,
    val diagnostics: List<Diagnostic>,
)

private fun RenderedDiagram.renderRasterBitmap(
    plan: RasterExportPlan,
    opaque: Boolean,
): AndroidRasterRenderResult {
    val config = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
    val bitmap = Bitmap.createBitmap(plan.widthPx, plan.heightPx, config)
    val canvas = Canvas(bitmap)
    if (plan.background != null && plan.background.a != 0) {
        canvas.drawColor(plan.background.toAndroidColor())
    }
    canvas.scale(plan.scale, plan.scale)
    canvas.translate(-bounds.left, -bounds.top)
    val diagnostics = ArrayList<Diagnostic>()
    renderCommands(canvas, drawCommands, diagnostics)
    return AndroidRasterRenderResult(bitmap = bitmap, diagnostics = diagnostics)
}

private fun renderCommands(
    canvas: Canvas,
    commands: List<DrawCommand>,
    diagnostics: MutableList<Diagnostic>,
) {
    for (command in commands.sortedBy { it.z }) {
        when (command) {
            is DrawCommand.FillRect -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = command.color.toAndroidColor()
                }
                canvas.drawRectShape(command.rect, command.corner, paint)
            }
            is DrawCommand.StrokeRect -> {
                val paint = command.stroke.toAndroidPaint(command.color, Paint.Style.STROKE)
                canvas.drawRectShape(command.rect, command.corner, paint)
            }
            is DrawCommand.FillPath -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = command.color.toAndroidColor()
                }
                canvas.drawPath(command.path.toAndroidPath(), paint)
            }
            is DrawCommand.StrokePath -> {
                val paint = command.stroke.toAndroidPaint(command.color, Paint.Style.STROKE)
                canvas.drawPath(command.path.toAndroidPath(), paint)
            }
            is DrawCommand.DrawText -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = command.color.toAndroidColor()
                    textSize = command.font.sizeSp
                    typeface = command.font.toAndroidTypeface()
                }
                val width = paint.measureText(command.text)
                val metrics = paint.fontMetrics
                val x = when (command.anchorX) {
                    TextAnchorX.Start -> command.origin.x
                    TextAnchorX.Center -> command.origin.x - width / 2f
                    TextAnchorX.End -> command.origin.x - width
                }
                val baseline = when (command.anchorY) {
                    TextAnchorY.Top -> command.origin.y - metrics.top
                    TextAnchorY.Middle -> command.origin.y - (metrics.ascent + metrics.descent) / 2f
                    TextAnchorY.Baseline -> command.origin.y
                    TextAnchorY.Bottom -> command.origin.y - metrics.bottom
                }
                canvas.drawText(command.text, x, baseline, paint)
            }
            is DrawCommand.DrawArrow -> {
                val linePaint = command.style.stroke.toAndroidPaint(command.style.color, Paint.Style.STROKE)
                canvas.drawLine(command.from.x, command.from.y, command.to.x, command.to.y, linePaint)
                drawArrowHead(canvas, command.to, command.from, command.style.head, linePaint)
                drawArrowHead(canvas, command.from, command.to, command.style.tail, linePaint)
            }
            is DrawCommand.DrawIcon -> {
                diagnostics += Diagnostic(
                    severity = Severity.WARNING,
                    code = "EXPORT-W001",
                    message = "DrawIcon raster fallback is not implemented on Android; icon '${command.name}' is omitted.",
                )
            }
            is DrawCommand.Group -> {
                canvas.save()
                applyTransform(canvas, command.transform)
                renderCommands(canvas, command.children, diagnostics)
                canvas.restore()
            }
            is DrawCommand.Clip -> {
                canvas.save()
                canvas.clipRect(command.rect.left, command.rect.top, command.rect.right, command.rect.bottom)
                renderCommands(canvas, command.children, diagnostics)
                canvas.restore()
            }
            is DrawCommand.Hyperlink -> Unit
        }
    }
}

private fun Canvas.drawRectShape(rect: Rect, corner: Float, paint: Paint) {
    if (corner > 0f) {
        drawRoundRect(RectF(rect.left, rect.top, rect.right, rect.bottom), corner, corner, paint)
    } else {
        drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
    }
}

private fun applyTransform(canvas: Canvas, transform: Transform) {
    if (transform.isIdentity) return
    val matrix = Matrix()
    matrix.postTranslate(transform.translate.x, transform.translate.y)
    matrix.postRotate(transform.rotateDeg)
    matrix.postScale(transform.scale, transform.scale)
    canvas.concat(matrix)
}

private fun PathCmd.toAndroidPath(): Path {
    val path = Path()
    for (op in ops) {
        when (op) {
            is PathOp.MoveTo -> path.moveTo(op.p.x, op.p.y)
            is PathOp.LineTo -> path.lineTo(op.p.x, op.p.y)
            is PathOp.QuadTo -> path.quadTo(op.ctrl.x, op.ctrl.y, op.end.x, op.end.y)
            is PathOp.CubicTo -> path.cubicTo(op.c1.x, op.c1.y, op.c2.x, op.c2.y, op.end.x, op.end.y)
            PathOp.Close -> path.close()
        }
    }
    return path
}

private fun Stroke.toAndroidPaint(color: Color, style: Paint.Style): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style
        this.color = color.toAndroidColor()
        strokeWidth = width
        strokeCap = when (cap) {
            Cap.Butt -> Paint.Cap.BUTT
            Cap.Round -> Paint.Cap.ROUND
            Cap.Square -> Paint.Cap.SQUARE
        }
        strokeJoin = when (join) {
            Join.Miter -> Paint.Join.MITER
            Join.Round -> Paint.Join.ROUND
            Join.Bevel -> Paint.Join.BEVEL
        }
        if (!dash.isNullOrEmpty()) {
            pathEffect = DashPathEffect(dash.toFloatArray(), 0f)
        }
    }

private fun com.hrm.diagram.core.draw.FontSpec.toAndroidTypeface(): Typeface {
    val style =
        (if (weight >= 600) Typeface.BOLD else Typeface.NORMAL) or
            (if (italic) Typeface.ITALIC else Typeface.NORMAL)
    return Typeface.create(family, style)
}

private fun Color.toAndroidColor(): Int = AndroidColor.argb(a, r, g, b)

private fun drawArrowHead(
    canvas: Canvas,
    tip: Point,
    tail: Point,
    head: ArrowHead,
    paint: Paint,
) {
    if (head == ArrowHead.None) return
    val angle = atan2((tip.y - tail.y).toDouble(), (tip.x - tail.x).toDouble())
    val size = 8.0
    fun point(distance: Double, offset: Double): Point {
        val c = cos(angle)
        val s = sin(angle)
        return Point(
            x = (tip.x - distance * c + offset * -s).toFloat(),
            y = (tip.y - distance * s + offset * c).toFloat(),
        )
    }
    when (head) {
        ArrowHead.Triangle, ArrowHead.Diamond, ArrowHead.Circle -> {
            if (head == ArrowHead.Circle) {
                val fill = Paint(paint).apply { style = Paint.Style.FILL }
                canvas.drawCircle(tip.x, tip.y, 4f, fill)
            } else {
                val fill = Paint(paint).apply { style = Paint.Style.FILL }
                val path = Path().apply {
                    moveTo(tip.x, tip.y)
                    val left = point(size, -size / 2.0)
                    val right = point(size, size / 2.0)
                    lineTo(left.x, left.y)
                    if (head == ArrowHead.Diamond) {
                        val back = point(size * 1.6, 0.0)
                        lineTo(back.x, back.y)
                    }
                    lineTo(right.x, right.y)
                    close()
                }
                canvas.drawPath(path, fill)
            }
        }
        ArrowHead.OpenTriangle, ArrowHead.OpenDiamond, ArrowHead.OpenCircle, ArrowHead.Bar, ArrowHead.Cross -> {
            when (head) {
                ArrowHead.OpenCircle -> canvas.drawCircle(tip.x, tip.y, 4f, paint)
                ArrowHead.Bar -> {
                    val left = point(0.0, -size / 2.0)
                    val right = point(0.0, size / 2.0)
                    canvas.drawLine(left.x, left.y, right.x, right.y, paint)
                }
                ArrowHead.Cross -> {
                    val a = point(size / 2.0, -size / 2.0)
                    val b = point(-size / 2.0, size / 2.0)
                    val c = point(size / 2.0, size / 2.0)
                    val d = point(-size / 2.0, -size / 2.0)
                    canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                    canvas.drawLine(c.x, c.y, d.x, d.y, paint)
                }
                ArrowHead.OpenDiamond -> {
                    val path = Path().apply {
                        val left = point(size / 2.0, -size / 3.0)
                        val back = point(size, 0.0)
                        val right = point(size / 2.0, size / 3.0)
                        moveTo(tip.x, tip.y)
                        lineTo(left.x, left.y)
                        lineTo(back.x, back.y)
                        lineTo(right.x, right.y)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
                ArrowHead.OpenTriangle -> {
                    val left = point(size, -size / 2.0)
                    val right = point(size, size / 2.0)
                    val path = Path().apply {
                        moveTo(left.x, left.y)
                        lineTo(tip.x, tip.y)
                        lineTo(right.x, right.y)
                    }
                    canvas.drawPath(path, paint)
                }
                else -> Unit
            }
        }
        ArrowHead.None -> Unit
    }
}

private fun encodeBitmap(
    bitmap: Bitmap,
    format: Bitmap.CompressFormat,
    quality: Int,
): ByteArray =
    ByteArrayOutputStream().use { out ->
        bitmap.compress(format, quality.coerceIn(1, 100), out)
        out.toByteArray()
    }
