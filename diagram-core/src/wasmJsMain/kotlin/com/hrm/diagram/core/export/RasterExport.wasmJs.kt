package com.hrm.diagram.core.export

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.draw.ArrowHead
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@DiagramApi
actual suspend fun RenderedDiagram.exportPng(
    options: RasterExportOptions,
): ExportArtifact<ByteArray> {
    val plan = planRasterExport(
        scale = options.scale,
        background = options.background,
        opaqueRequired = false,
    )
    val render = renderBrowserCanvas(plan = plan)
    return runCatching { render.canvas.toBlobBytes("image/png", 1.0) }.fold(
        onSuccess = { bytes ->
            ExportArtifact(
                value = bytes,
                mimeType = "image/png",
                widthPx = plan.widthPx,
                heightPx = plan.heightPx,
                diagnostics = plan.diagnostics + render.diagnostics,
            )
        },
        onFailure = {
            fallbackRasterExport(plan, "image/png", "Wasm PNG export failed: ${it.message ?: it::class.simpleName}.")
        },
    )
}

@DiagramApi
actual suspend fun RenderedDiagram.exportJpeg(
    options: JpegExportOptions,
): ExportArtifact<ByteArray> {
    val plan = planRasterExport(
        scale = options.scale,
        background = options.background,
        opaqueRequired = true,
    )
    val render = renderBrowserCanvas(plan = plan)
    return runCatching { render.canvas.toBlobBytes("image/jpeg", options.quality.coerceIn(1, 100) / 100.0) }.fold(
        onSuccess = { bytes ->
            ExportArtifact(
                value = bytes,
                mimeType = "image/jpeg",
                widthPx = plan.widthPx,
                heightPx = plan.heightPx,
                diagnostics = plan.diagnostics + render.diagnostics,
            )
        },
        onFailure = {
            fallbackRasterExport(plan, "image/jpeg", "Wasm JPEG export failed: ${it.message ?: it::class.simpleName}.")
        },
    )
}

private data class BrowserCanvasRenderResult(
    val canvas: WasmCanvasElement,
    val diagnostics: List<Diagnostic>,
)

private fun RenderedDiagram.renderBrowserCanvas(plan: RasterExportPlan): BrowserCanvasRenderResult {
    val canvas = document.createElement("canvas")
    canvas.width = plan.widthPx
    canvas.height = plan.heightPx
    val ctx = canvas.getContext("2d")
        ?: return BrowserCanvasRenderResult(canvas, listOf(exportWarning("Unable to create browser 2D canvas context.")))
    if (plan.background != null && plan.background.a != 0) {
        ctx.fillStyle = plan.background.toCssColor()
        ctx.fillRect(0.0, 0.0, plan.widthPx.toDouble(), plan.heightPx.toDouble())
    }
    ctx.scale(plan.scale.toDouble(), plan.scale.toDouble())
    ctx.translate(-bounds.left.toDouble(), -bounds.top.toDouble())
    val diagnostics = ArrayList<Diagnostic>()
    renderCommands(ctx, drawCommands, diagnostics)
    return BrowserCanvasRenderResult(canvas = canvas, diagnostics = diagnostics)
}

private fun renderCommands(
    ctx: WasmCanvasRenderingContext2D,
    commands: List<DrawCommand>,
    diagnostics: MutableList<Diagnostic>,
) {
    for (command in commands.sortedBy { it.z }) {
        when (command) {
            is DrawCommand.FillRect -> {
                ctx.fillStyle = command.color.toCssColor()
                if (command.corner > 0f) {
                    ctx.beginPath()
                    roundedRectPath(ctx, command.rect, command.corner)
                    ctx.fill()
                } else {
                    ctx.fillRect(command.rect.left.toDouble(), command.rect.top.toDouble(), command.rect.size.width.toDouble(), command.rect.size.height.toDouble())
                }
            }
            is DrawCommand.StrokeRect -> {
                applyStroke(ctx, command.stroke, command.color)
                if (command.corner > 0f) {
                    ctx.beginPath()
                    roundedRectPath(ctx, command.rect, command.corner)
                    ctx.stroke()
                } else {
                    ctx.strokeRect(command.rect.left.toDouble(), command.rect.top.toDouble(), command.rect.size.width.toDouble(), command.rect.size.height.toDouble())
                }
            }
            is DrawCommand.FillPath -> {
                ctx.fillStyle = command.color.toCssColor()
                drawPath(ctx, command.path)
                ctx.fill()
            }
            is DrawCommand.StrokePath -> {
                applyStroke(ctx, command.stroke, command.color)
                drawPath(ctx, command.path)
                ctx.stroke()
            }
            is DrawCommand.DrawText -> {
                val measured = command.measuredBounds
                val width = measured?.size?.width?.toDouble() ?: ctx.measureTextWidth(command.text, command.font)
                val height = measured?.size?.height?.toDouble() ?: command.font.sizeSp.toDouble()
                ctx.font = command.font.toCanvasFont()
                ctx.fillStyle = command.color.toCssColor()
                val x = when (command.anchorX) {
                    TextAnchorX.Start -> command.origin.x.toDouble()
                    TextAnchorX.Center -> command.origin.x - width / 2.0
                    TextAnchorX.End -> command.origin.x - width
                }
                val y = when (command.anchorY) {
                    TextAnchorY.Top -> command.origin.y + height
                    TextAnchorY.Middle -> command.origin.y + height / 2.0 - 2.0
                    TextAnchorY.Baseline -> measured?.bottom?.toDouble() ?: command.origin.y.toDouble()
                    TextAnchorY.Bottom -> command.origin.y.toDouble()
                }
                ctx.fillText(command.text, x, y)
            }
            is DrawCommand.DrawArrow -> {
                applyStroke(ctx, command.style.stroke, command.style.color)
                ctx.beginPath()
                ctx.moveTo(command.from.x.toDouble(), command.from.y.toDouble())
                ctx.lineTo(command.to.x.toDouble(), command.to.y.toDouble())
                ctx.stroke()
                drawArrowHead(ctx, command.to, command.from, command.style.head, command.style.color, fill = true)
                drawArrowHead(ctx, command.from, command.to, command.style.tail, command.style.color, fill = true)
            }
            is DrawCommand.DrawIcon -> diagnostics += exportWarning("DrawIcon raster fallback is not implemented on Wasm; icon '${command.name}' is omitted.")
            is DrawCommand.Group -> {
                ctx.save()
                if (!command.transform.isIdentity) {
                    ctx.translate(command.transform.translate.x.toDouble(), command.transform.translate.y.toDouble())
                    ctx.rotate(command.transform.rotateDeg * PI / 180.0)
                    ctx.scale(command.transform.scale.toDouble(), command.transform.scale.toDouble())
                }
                renderCommands(ctx, command.children, diagnostics)
                ctx.restore()
            }
            is DrawCommand.Clip -> {
                ctx.save()
                ctx.beginPath()
                ctx.rect(command.rect.left.toDouble(), command.rect.top.toDouble(), command.rect.size.width.toDouble(), command.rect.size.height.toDouble())
                ctx.clip()
                renderCommands(ctx, command.children, diagnostics)
                ctx.restore()
            }
            is DrawCommand.Hyperlink -> Unit
        }
    }
}

private fun drawPath(ctx: WasmCanvasRenderingContext2D, path: PathCmd) {
    ctx.beginPath()
    for (op in path.ops) {
        when (op) {
            is PathOp.MoveTo -> ctx.moveTo(op.p.x.toDouble(), op.p.y.toDouble())
            is PathOp.LineTo -> ctx.lineTo(op.p.x.toDouble(), op.p.y.toDouble())
            is PathOp.QuadTo -> ctx.quadraticCurveTo(op.ctrl.x.toDouble(), op.ctrl.y.toDouble(), op.end.x.toDouble(), op.end.y.toDouble())
            is PathOp.CubicTo -> ctx.bezierCurveTo(op.c1.x.toDouble(), op.c1.y.toDouble(), op.c2.x.toDouble(), op.c2.y.toDouble(), op.end.x.toDouble(), op.end.y.toDouble())
            PathOp.Close -> ctx.closePath()
        }
    }
}

private fun roundedRectPath(ctx: WasmCanvasRenderingContext2D, rect: com.hrm.diagram.core.draw.Rect, corner: Float) {
    val r = corner.toDouble()
    val left = rect.left.toDouble()
    val top = rect.top.toDouble()
    val right = rect.right.toDouble()
    val bottom = rect.bottom.toDouble()
    ctx.moveTo(left + r, top)
    ctx.lineTo(right - r, top)
    ctx.quadraticCurveTo(right, top, right, top + r)
    ctx.lineTo(right, bottom - r)
    ctx.quadraticCurveTo(right, bottom, right - r, bottom)
    ctx.lineTo(left + r, bottom)
    ctx.quadraticCurveTo(left, bottom, left, bottom - r)
    ctx.lineTo(left, top + r)
    ctx.quadraticCurveTo(left, top, left + r, top)
    ctx.closePath()
}

private fun applyStroke(ctx: WasmCanvasRenderingContext2D, stroke: com.hrm.diagram.core.draw.Stroke, color: Color) {
    ctx.strokeStyle = color.toCssColor()
    ctx.lineWidth = stroke.width.toDouble()
    ctx.lineCap = when (stroke.cap) {
        com.hrm.diagram.core.draw.Cap.Butt -> "butt"
        com.hrm.diagram.core.draw.Cap.Round -> "round"
        com.hrm.diagram.core.draw.Cap.Square -> "square"
    }
    ctx.lineJoin = when (stroke.join) {
        com.hrm.diagram.core.draw.Join.Miter -> "miter"
        com.hrm.diagram.core.draw.Join.Round -> "round"
        com.hrm.diagram.core.draw.Join.Bevel -> "bevel"
    }
}

private fun Color.toCssColor(): String = "rgba($r,$g,$b,${a / 255.0})"

private fun com.hrm.diagram.core.draw.FontSpec.toCanvasFont(): String {
    val italicPart = if (italic) "italic " else ""
    val weightPart = if (weight >= 600) "${weight} " else ""
    return "$italicPart$weightPart${sizeSp}px $family"
}

private fun WasmCanvasRenderingContext2D.measureTextWidth(text: String, font: com.hrm.diagram.core.draw.FontSpec): Double {
    save()
    this.font = font.toCanvasFont()
        val width = measureText(text).width
    restore()
    return width
}

private fun drawArrowHead(
    ctx: WasmCanvasRenderingContext2D,
    tip: Point,
    tail: Point,
    head: ArrowHead,
    color: Color,
    fill: Boolean,
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
    ctx.beginPath()
    ctx.strokeStyle = color.toCssColor()
    ctx.fillStyle = color.toCssColor()
    when (head) {
        ArrowHead.Triangle, ArrowHead.Diamond, ArrowHead.Circle -> {
            if (head == ArrowHead.Circle) {
                ctx.arc(tip.x.toDouble(), tip.y.toDouble(), 4.0, 0.0, 2 * PI)
                if (fill) ctx.fill() else ctx.stroke()
            } else {
                val left = point(size, -size / 2.0)
                val right = point(size, size / 2.0)
                ctx.moveTo(tip.x.toDouble(), tip.y.toDouble())
                ctx.lineTo(left.x.toDouble(), left.y.toDouble())
                if (head == ArrowHead.Diamond) {
                    val back = point(size * 1.6, 0.0)
                    ctx.lineTo(back.x.toDouble(), back.y.toDouble())
                }
                ctx.lineTo(right.x.toDouble(), right.y.toDouble())
                ctx.closePath()
                if (fill) ctx.fill() else ctx.stroke()
            }
        }
        ArrowHead.OpenTriangle, ArrowHead.OpenDiamond, ArrowHead.OpenCircle, ArrowHead.Bar, ArrowHead.Cross -> {
            when (head) {
                ArrowHead.OpenCircle -> {
                    ctx.arc(tip.x.toDouble(), tip.y.toDouble(), 4.0, 0.0, 2 * PI)
                    ctx.stroke()
                }
                ArrowHead.Bar -> {
                    val left = point(0.0, -size / 2.0)
                    val right = point(0.0, size / 2.0)
                    ctx.moveTo(left.x.toDouble(), left.y.toDouble())
                    ctx.lineTo(right.x.toDouble(), right.y.toDouble())
                    ctx.stroke()
                }
                ArrowHead.Cross -> {
                    val a = point(size / 2.0, -size / 2.0)
                    val b = point(-size / 2.0, size / 2.0)
                    val c = point(size / 2.0, size / 2.0)
                    val d = point(-size / 2.0, -size / 2.0)
                    ctx.moveTo(a.x.toDouble(), a.y.toDouble())
                    ctx.lineTo(b.x.toDouble(), b.y.toDouble())
                    ctx.moveTo(c.x.toDouble(), c.y.toDouble())
                    ctx.lineTo(d.x.toDouble(), d.y.toDouble())
                    ctx.stroke()
                }
                ArrowHead.OpenDiamond -> {
                    val left = point(size / 2.0, -size / 3.0)
                    val back = point(size, 0.0)
                    val right = point(size / 2.0, size / 3.0)
                    ctx.moveTo(tip.x.toDouble(), tip.y.toDouble())
                    ctx.lineTo(left.x.toDouble(), left.y.toDouble())
                    ctx.lineTo(back.x.toDouble(), back.y.toDouble())
                    ctx.lineTo(right.x.toDouble(), right.y.toDouble())
                    ctx.closePath()
                    ctx.stroke()
                }
                ArrowHead.OpenTriangle -> {
                    val left = point(size, -size / 2.0)
                    val right = point(size, size / 2.0)
                    ctx.moveTo(left.x.toDouble(), left.y.toDouble())
                    ctx.lineTo(tip.x.toDouble(), tip.y.toDouble())
                    ctx.lineTo(right.x.toDouble(), right.y.toDouble())
                    ctx.stroke()
                }
                ArrowHead.None -> Unit
            }
        }
        ArrowHead.None -> Unit
    }
}

private suspend fun WasmCanvasElement.toBlobBytes(type: String, quality: Double): ByteArray {
    val blob = suspendCoroutine<WasmBlob> { continuation ->
        toBlob(
            callback = { created ->
                if (created != null) continuation.resume(created) else continuation.resumeWithException(IllegalStateException("Canvas.toBlob returned null for $type"))
            },
            type = type,
            quality = quality,
        )
    }
    val buffer = blob.readAsArrayBuffer()
    val uint8 = WasmUint8Array(buffer)
    return ByteArray(uint8.length) { index -> uint8[index] }
}

private suspend fun WasmBlob.readAsArrayBuffer(): WasmArrayBuffer =
    suspendCoroutine<WasmArrayBuffer> { continuation ->
        val reader = WasmFileReader()
        reader.onloadend = {
            val result = reader.result
            if (result != null) {
                continuation.resume(result)
            } else {
                continuation.resumeWithException(IllegalStateException("FileReader returned null ArrayBuffer"))
            }
        }
        reader.onerror = {
            continuation.resumeWithException(IllegalStateException("Failed to read blob as ArrayBuffer"))
        }
        reader.readAsArrayBuffer(this)
    }

private fun exportWarning(message: String): Diagnostic = Diagnostic(
    severity = Severity.WARNING,
    code = "EXPORT-W001",
    message = message,
)

external val document: WasmDocument

external interface WasmDocument {
    fun createElement(tag: String): WasmCanvasElement
}

external interface WasmCanvasElement {
    var width: Int
    var height: Int
    fun getContext(kind: String): WasmCanvasRenderingContext2D?
    fun toBlob(callback: (WasmBlob?) -> Unit, type: String = definedExternally, quality: Double = definedExternally)
}

external interface WasmCanvasRenderingContext2D {
    var fillStyle: String
    var strokeStyle: String
    var font: String
    var lineWidth: Double
    var lineCap: String
    var lineJoin: String
    fun fillRect(x: Double, y: Double, width: Double, height: Double)
    fun strokeRect(x: Double, y: Double, width: Double, height: Double)
    fun scale(x: Double, y: Double)
    fun translate(x: Double, y: Double)
    fun rotate(angle: Double)
    fun save()
    fun restore()
    fun beginPath()
    fun closePath()
    fun moveTo(x: Double, y: Double)
    fun lineTo(x: Double, y: Double)
    fun quadraticCurveTo(cpx: Double, cpy: Double, x: Double, y: Double)
    fun bezierCurveTo(cp1x: Double, cp1y: Double, cp2x: Double, cp2y: Double, x: Double, y: Double)
    fun rect(x: Double, y: Double, width: Double, height: Double)
    fun clip()
    fun fill()
    fun stroke()
    fun fillText(text: String, x: Double, y: Double)
    fun arc(x: Double, y: Double, radius: Double, startAngle: Double, endAngle: Double)
    fun measureText(text: String): WasmTextMetrics
}

external interface WasmTextMetrics {
    val width: Double
}

external interface WasmBlob

external class WasmArrayBuffer

external class WasmUint8Array(buffer: WasmArrayBuffer) {
    val length: Int
    operator fun get(index: Int): Byte
}

external class WasmFileReader {
    var result: WasmArrayBuffer?
    var onloadend: (() -> Unit)?
    var onerror: (() -> Unit)?
    fun readAsArrayBuffer(blob: WasmBlob)
}
