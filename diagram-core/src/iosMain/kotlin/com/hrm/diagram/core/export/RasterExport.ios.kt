@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hrm.diagram.core.export

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.draw.ArrowHead
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGContextClipToRect
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextRotateCTM
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UILabel
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.NSTextAlignmentLeft
import platform.UIKit.NSTextAlignmentRight
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.posix.memcpy
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@DiagramApi
actual suspend fun RenderedDiagram.exportPng(
    options: RasterExportOptions,
): ExportArtifact<ByteArray> {
    val plan = planRasterExport(
        scale = options.scale,
        background = options.background,
        opaqueRequired = false,
    )
    val render = renderRasterImage(plan = plan, opaque = false)
    val data = UIImagePNGRepresentation(render.image)
    return ExportArtifact(
        value = data?.toByteArray() ?: ByteArray(0),
        mimeType = "image/png",
        widthPx = plan.widthPx,
        heightPx = plan.heightPx,
        diagnostics = plan.diagnostics + render.diagnostics,
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
    val render = renderRasterImage(plan = plan, opaque = true)
    val data = UIImageJPEGRepresentation(render.image, options.quality.coerceIn(1, 100) / 100.0)
    return ExportArtifact(
        value = data?.toByteArray() ?: ByteArray(0),
        mimeType = "image/jpeg",
        widthPx = plan.widthPx,
        heightPx = plan.heightPx,
        diagnostics = plan.diagnostics + render.diagnostics,
    )
}

private data class IosRasterRenderResult(
    val image: UIImage,
    val diagnostics: List<Diagnostic>,
)

private fun RenderedDiagram.renderRasterImage(
    plan: RasterExportPlan,
    opaque: Boolean,
): IosRasterRenderResult {
    UIGraphicsBeginImageContextWithOptions(
        size = CGSizeMake(plan.widthPx.toDouble(), plan.heightPx.toDouble()),
        opaque = opaque,
        scale = 1.0,
    )
    try {
        val context = UIGraphicsGetCurrentContext()
            ?: return IosRasterRenderResult(UIImage(), listOf(exportWarning("Unable to create iOS graphics context.")))
        if (plan.background != null && plan.background.a != 0) {
            plan.background.toUiColor().setFill()
            UIBezierPath.bezierPathWithRect(CGRectMake(0.0, 0.0, plan.widthPx.toDouble(), plan.heightPx.toDouble())).fill()
        }
        CGContextScaleCTM(context, plan.scale.toDouble(), plan.scale.toDouble())
        CGContextTranslateCTM(context, -bounds.left.toDouble(), -bounds.top.toDouble())
        val diagnostics = ArrayList<Diagnostic>()
        renderCommands(drawCommands, diagnostics)
        val image = UIGraphicsGetImageFromCurrentImageContext()
            ?: UIImage()
        return IosRasterRenderResult(image = image, diagnostics = diagnostics)
    } finally {
        UIGraphicsEndImageContext()
    }
}

private fun renderCommands(
    commands: List<DrawCommand>,
    diagnostics: MutableList<Diagnostic>,
) {
    for (command in commands.sortedBy { it.z }) {
        when (command) {
            is DrawCommand.FillRect -> {
                command.color.toUiColor().setFill()
                bezierRect(command.rect, command.corner).fill()
            }
            is DrawCommand.StrokeRect -> {
                val path = bezierRect(command.rect, command.corner)
                configureStroke(path, command.stroke, command.color, diagnostics)
                path.stroke()
            }
            is DrawCommand.FillPath -> {
                command.color.toUiColor().setFill()
                bezierPath(command.path).fill()
            }
            is DrawCommand.StrokePath -> {
                val path = bezierPath(command.path)
                configureStroke(path, command.stroke, command.color, diagnostics)
                path.stroke()
            }
            is DrawCommand.DrawText -> drawText(command)
            is DrawCommand.DrawArrow -> {
                configureStroke(null, command.style.stroke, command.style.color, diagnostics)
                val path = UIBezierPath().apply {
                    moveToPoint(command.from.toCgPoint())
                    addLineToPoint(command.to.toCgPoint())
                    lineWidth = command.style.stroke.width.toDouble()
                }
                path.stroke()
                drawArrowHead(command.to, command.from, command.style.head, command.style.color, diagnostics)
                drawArrowHead(command.from, command.to, command.style.tail, command.style.color, diagnostics)
            }
            is DrawCommand.DrawIcon -> diagnostics += exportWarning("DrawIcon raster fallback is not implemented on iOS; icon '${command.name}' is omitted.")
            is DrawCommand.Group -> {
                val context = UIGraphicsGetCurrentContext()
                if (context != null) {
                    CGContextSaveGState(context)
                    if (!command.transform.isIdentity) {
                        CGContextTranslateCTM(context, command.transform.translate.x.toDouble(), command.transform.translate.y.toDouble())
                        CGContextRotateCTM(context, command.transform.rotateDeg * PI / 180.0)
                        CGContextScaleCTM(context, command.transform.scale.toDouble(), command.transform.scale.toDouble())
                    }
                    renderCommands(command.children, diagnostics)
                    CGContextRestoreGState(context)
                } else {
                    renderCommands(command.children, diagnostics)
                }
            }
            is DrawCommand.Clip -> {
                val context = UIGraphicsGetCurrentContext()
                if (context != null) {
                    CGContextSaveGState(context)
                    CGContextClipToRect(context, command.rect.toCgRect())
                    renderCommands(command.children, diagnostics)
                    CGContextRestoreGState(context)
                } else {
                    renderCommands(command.children, diagnostics)
                }
            }
            is DrawCommand.Hyperlink -> Unit
        }
    }
}

private fun drawText(command: DrawCommand.DrawText) {
    val measured = command.measuredBounds
    val width = measured?.size?.width ?: command.maxWidth ?: (command.text.length * command.font.sizeSp * 0.6f)
    val height = measured?.size?.height ?: (command.font.sizeSp * 1.2f)
    val x = when (command.anchorX) {
        TextAnchorX.Start -> command.origin.x
        TextAnchorX.Center -> command.origin.x - width / 2f
        TextAnchorX.End -> command.origin.x - width
    }
    val y = when (command.anchorY) {
        TextAnchorY.Top -> command.origin.y
        TextAnchorY.Middle -> command.origin.y - height / 2f
        TextAnchorY.Baseline -> measured?.top ?: (command.origin.y - height)
        TextAnchorY.Bottom -> command.origin.y - height
    }
    val label = UILabel(frame = CGRectMake(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())).apply {
        text = command.text
        font = command.font.toUIFont()
        textColor = command.color.toUiColor()
        numberOfLines = 0
        lineBreakMode = 0
        textAlignment = when (command.anchorX) {
            TextAnchorX.Start -> NSTextAlignmentLeft
            TextAnchorX.Center -> NSTextAlignmentCenter
            TextAnchorX.End -> NSTextAlignmentRight
        }
    }
    label.drawTextInRect(CGRectMake(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble()))
}

private fun bezierRect(rect: Rect, corner: Float): UIBezierPath =
    if (corner > 0f) {
        UIBezierPath.bezierPathWithRoundedRect(rect.toCgRect(), cornerRadius = corner.toDouble())
    } else {
        UIBezierPath.bezierPathWithRect(rect.toCgRect())
    }

private fun bezierPath(path: PathCmd): UIBezierPath = UIBezierPath().apply {
    for (op in path.ops) {
        when (op) {
            is PathOp.MoveTo -> moveToPoint(op.p.toCgPoint())
            is PathOp.LineTo -> addLineToPoint(op.p.toCgPoint())
            is PathOp.QuadTo -> addQuadCurveToPoint(op.end.toCgPoint(), controlPoint = op.ctrl.toCgPoint())
            is PathOp.CubicTo -> addCurveToPoint(op.end.toCgPoint(), controlPoint1 = op.c1.toCgPoint(), controlPoint2 = op.c2.toCgPoint())
            PathOp.Close -> closePath()
        }
    }
}

private fun configureStroke(path: UIBezierPath?, stroke: Stroke, color: Color, diagnostics: MutableList<Diagnostic>) {
    color.toUiColor().setStroke()
    path?.lineWidth = stroke.width.toDouble()
    if (!stroke.dash.isNullOrEmpty()) {
        diagnostics += exportWarning("Dashed stroke is approximated on iOS raster export.")
    }
}

private fun drawArrowHead(
    tip: Point,
    tail: Point,
    head: ArrowHead,
    color: Color,
    diagnostics: MutableList<Diagnostic>,
) {
    if (head == ArrowHead.None) return
    val size = 8f
    val (left, right, back) = arrowGeometry(tip, tail, size)
    when (head) {
        ArrowHead.Triangle, ArrowHead.Diamond, ArrowHead.Circle -> {
            color.toUiColor().setFill()
            val path = UIBezierPath()
            when (head) {
                ArrowHead.Circle -> path.appendPath(UIBezierPath.bezierPathWithOvalInRect(CGRectMake((tip.x - 4f).toDouble(), (tip.y - 4f).toDouble(), 8.0, 8.0)))
                ArrowHead.Triangle -> {
                    path.moveToPoint(tip.toCgPoint())
                    path.addLineToPoint(left.toCgPoint())
                    path.addLineToPoint(right.toCgPoint())
                    path.closePath()
                }
                ArrowHead.Diamond -> {
                    path.moveToPoint(tip.toCgPoint())
                    path.addLineToPoint(left.toCgPoint())
                    path.addLineToPoint(back.toCgPoint())
                    path.addLineToPoint(right.toCgPoint())
                    path.closePath()
                }
            }
            path.fill()
        }
        ArrowHead.OpenTriangle, ArrowHead.OpenDiamond, ArrowHead.OpenCircle, ArrowHead.Bar, ArrowHead.Cross -> {
            color.toUiColor().setStroke()
            val path = UIBezierPath()
            path.lineWidth = 1.5
            when (head) {
                ArrowHead.OpenCircle -> path.appendPath(UIBezierPath.bezierPathWithOvalInRect(CGRectMake((tip.x - 4f).toDouble(), (tip.y - 4f).toDouble(), 8.0, 8.0)))
                ArrowHead.Bar -> {
                    path.moveToPoint(left.toCgPoint())
                    path.addLineToPoint(right.toCgPoint())
                }
                ArrowHead.Cross -> {
                    val (c1, c2, c3, c4) = crossGeometry(tip, tail, size)
                    path.moveToPoint(c1.toCgPoint())
                    path.addLineToPoint(c2.toCgPoint())
                    path.moveToPoint(c3.toCgPoint())
                    path.addLineToPoint(c4.toCgPoint())
                }
                ArrowHead.OpenDiamond -> {
                    path.moveToPoint(tip.toCgPoint())
                    path.addLineToPoint(left.toCgPoint())
                    path.addLineToPoint(back.toCgPoint())
                    path.addLineToPoint(right.toCgPoint())
                    path.closePath()
                }
                ArrowHead.OpenTriangle -> {
                    path.moveToPoint(left.toCgPoint())
                    path.addLineToPoint(tip.toCgPoint())
                    path.addLineToPoint(right.toCgPoint())
                }
            }
            path.stroke()
        }
        ArrowHead.None -> Unit
    }
}

private fun arrowGeometry(tip: Point, tail: Point, size: Float): Triple<Point, Point, Point> {
    val dx = tip.x - tail.x
    val dy = tip.y - tail.y
    val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: 1f
    val ux = dx / len
    val uy = dy / len
    val baseX = tip.x - ux * size
    val baseY = tip.y - uy * size
    val nx = -uy
    val ny = ux
    val left = Point(baseX + nx * size * 0.5f, baseY + ny * size * 0.5f)
    val right = Point(baseX - nx * size * 0.5f, baseY - ny * size * 0.5f)
    val back = Point(tip.x - ux * size * 1.6f, tip.y - uy * size * 1.6f)
    return Triple(left, right, back)
}

private fun crossGeometry(tip: Point, tail: Point, size: Float): List<Point> {
    val dx = tip.x - tail.x
    val dy = tip.y - tail.y
    val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.0001f } ?: 1f
    val ux = dx / len
    val uy = dy / len
    val nx = -uy
    val ny = ux
    val c1 = Point(tip.x - ux * size / 2f + nx * size / 2f, tip.y - uy * size / 2f + ny * size / 2f)
    val c2 = Point(tip.x + ux * size / 2f - nx * size / 2f, tip.y + uy * size / 2f - ny * size / 2f)
    val c3 = Point(tip.x - ux * size / 2f - nx * size / 2f, tip.y - uy * size / 2f - ny * size / 2f)
    val c4 = Point(tip.x + ux * size / 2f + nx * size / 2f, tip.y + uy * size / 2f + ny * size / 2f)
    return listOf(c1, c2, c3, c4)
}

private fun Point.toCgPoint() = CGPointMake(x.toDouble(), y.toDouble())

private fun Rect.toCgRect() = CGRectMake(left.toDouble(), top.toDouble(), size.width.toDouble(), size.height.toDouble())

private fun Color.toUiColor(): UIColor = UIColor.colorWithRed(
    red = r / 255.0,
    green = g / 255.0,
    blue = b / 255.0,
    alpha = a / 255.0,
)

private fun com.hrm.diagram.core.draw.FontSpec.toUIFont(): UIFont =
    when {
        italic -> UIFont.italicSystemFontOfSize(sizeSp.toDouble())
        weight >= 600 -> UIFont.boldSystemFontOfSize(sizeSp.toDouble())
        else -> UIFont.systemFontOfSize(sizeSp.toDouble())
    }

private fun exportWarning(message: String): Diagnostic = Diagnostic(
    severity = Severity.WARNING,
    code = "EXPORT-W001",
    message = message,
)

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
