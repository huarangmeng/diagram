package com.hrm.diagram.core.export

import com.hrm.diagram.core.DiagramApi
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
import java.awt.BasicStroke
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
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
    val render = renderRasterImage(plan = plan, opaque = false)
    return ExportArtifact(
        value = encodePng(render.image),
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
    return ExportArtifact(
        value = encodeJpeg(render.image, options.quality),
        mimeType = "image/jpeg",
        widthPx = plan.widthPx,
        heightPx = plan.heightPx,
        diagnostics = plan.diagnostics + render.diagnostics,
    )
}

private data class RasterRenderResult(
    val image: BufferedImage,
    val diagnostics: List<Diagnostic>,
)

private fun RenderedDiagram.renderRasterImage(
    plan: RasterExportPlan,
    opaque: Boolean,
): RasterRenderResult {
    val imageType = if (opaque) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
    val image = BufferedImage(plan.widthPx, plan.heightPx, imageType)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        if (plan.background != null && plan.background.a != 0) {
            graphics.color = plan.background.toAwtColor()
            graphics.fillRect(0, 0, image.width, image.height)
        }
        val root = AffineTransform()
        root.scale(plan.scale.toDouble(), plan.scale.toDouble())
        root.translate(-bounds.left.toDouble(), -bounds.top.toDouble())
        graphics.transform = root
        val diagnostics = ArrayList<Diagnostic>()
        renderCommands(graphics, drawCommands, diagnostics)
        return RasterRenderResult(image = image, diagnostics = diagnostics)
    } finally {
        graphics.dispose()
    }
}

private fun renderCommands(
    graphics: java.awt.Graphics2D,
    commands: List<DrawCommand>,
    diagnostics: MutableList<Diagnostic>,
) {
    for ((_, command) in commands.withIndex().sortedBy { it.value.z }) {
        when (command) {
            is DrawCommand.FillRect -> {
                graphics.color = command.color.toAwtColor()
                graphics.fill(rectShape(command.rect, command.corner))
            }
            is DrawCommand.StrokeRect -> {
                graphics.color = command.color.toAwtColor()
                graphics.stroke = command.stroke.toAwtStroke()
                graphics.draw(rectShape(command.rect, command.corner))
            }
            is DrawCommand.FillPath -> {
                graphics.color = command.color.toAwtColor()
                graphics.fill(command.path.toAwtPath())
            }
            is DrawCommand.StrokePath -> {
                graphics.color = command.color.toAwtColor()
                graphics.stroke = command.stroke.toAwtStroke()
                graphics.draw(command.path.toAwtPath())
            }
            is DrawCommand.DrawText -> {
                val font = command.font.toAwtFont()
                graphics.font = font
                graphics.color = command.color.toAwtColor()
                val metrics = graphics.getFontMetrics(font)
                val width = metrics.stringWidth(command.text).toFloat()
                val x = when (command.anchorX) {
                    TextAnchorX.Start -> command.origin.x
                    TextAnchorX.Center -> command.origin.x - width / 2f
                    TextAnchorX.End -> command.origin.x - width
                }
                val baseline = when (command.anchorY) {
                    TextAnchorY.Top -> command.origin.y + metrics.ascent
                    TextAnchorY.Middle -> command.origin.y + (metrics.ascent - metrics.descent) / 2f
                    TextAnchorY.Baseline -> command.origin.y
                    TextAnchorY.Bottom -> command.origin.y - metrics.descent
                }
                graphics.drawString(command.text, x, baseline)
            }
            is DrawCommand.DrawArrow -> {
                graphics.color = command.style.color.toAwtColor()
                graphics.stroke = command.style.stroke.toAwtStroke()
                graphics.draw(java.awt.geom.Line2D.Float(command.from.x, command.from.y, command.to.x, command.to.y))
                drawArrowHead(graphics, command.to, command.from, command.style.head, command.style.color)
                drawArrowHead(graphics, command.from, command.to, command.style.tail, command.style.color)
            }
            is DrawCommand.DrawIcon -> {
                diagnostics += Diagnostic(
                    severity = Severity.WARNING,
                    code = "EXPORT-W001",
                    message = "DrawIcon raster fallback is not implemented; icon '${command.name}' is omitted.",
                )
            }
            is DrawCommand.Group -> {
                val child = graphics.create() as java.awt.Graphics2D
                try {
                    applyTransform(child, command.transform)
                    renderCommands(child, command.children, diagnostics)
                } finally {
                    child.dispose()
                }
            }
            is DrawCommand.Clip -> {
                val child = graphics.create() as java.awt.Graphics2D
                try {
                    child.clip(rectShape(command.rect, corner = 0f))
                    renderCommands(child, command.children, diagnostics)
                } finally {
                    child.dispose()
                }
            }
            is DrawCommand.Hyperlink -> Unit
        }
    }
}

private fun applyTransform(graphics: java.awt.Graphics2D, transform: Transform) {
    if (transform.isIdentity) return
    val affine = AffineTransform()
    affine.translate(transform.translate.x.toDouble(), transform.translate.y.toDouble())
    affine.rotate(transform.rotateDeg * PI / 180.0)
    affine.scale(transform.scale.toDouble(), transform.scale.toDouble())
    graphics.transform(affine)
}

private fun rectShape(rect: Rect, corner: Float): java.awt.Shape =
    if (corner > 0f) {
        RoundRectangle2D.Float(rect.left, rect.top, rect.size.width, rect.size.height, corner * 2f, corner * 2f)
    } else {
        Rectangle2D.Float(rect.left, rect.top, rect.size.width, rect.size.height)
    }

private fun PathCmd.toAwtPath(): Path2D.Float {
    val path = Path2D.Float()
    for (op in ops) {
        when (op) {
            is PathOp.MoveTo -> path.moveTo(op.p.x.toDouble(), op.p.y.toDouble())
            is PathOp.LineTo -> path.lineTo(op.p.x.toDouble(), op.p.y.toDouble())
            is PathOp.QuadTo -> path.quadTo(op.ctrl.x.toDouble(), op.ctrl.y.toDouble(), op.end.x.toDouble(), op.end.y.toDouble())
            is PathOp.CubicTo -> path.curveTo(op.c1.x.toDouble(), op.c1.y.toDouble(), op.c2.x.toDouble(), op.c2.y.toDouble(), op.end.x.toDouble(), op.end.y.toDouble())
            PathOp.Close -> path.closePath()
        }
    }
    return path
}

private fun Color.toAwtColor(): java.awt.Color = java.awt.Color(r, g, b, a)

private fun Stroke.toAwtStroke(): BasicStroke = BasicStroke(
    width,
    when (cap) {
        Cap.Butt -> BasicStroke.CAP_BUTT
        Cap.Round -> BasicStroke.CAP_ROUND
        Cap.Square -> BasicStroke.CAP_SQUARE
    },
    when (join) {
        Join.Miter -> BasicStroke.JOIN_MITER
        Join.Round -> BasicStroke.JOIN_ROUND
        Join.Bevel -> BasicStroke.JOIN_BEVEL
    },
    10f,
    dash?.toFloatArray(),
    0f,
)

private fun com.hrm.diagram.core.draw.FontSpec.toAwtFont(): Font {
    val style =
        (if (weight >= 600) Font.BOLD else Font.PLAIN) or
            (if (italic) Font.ITALIC else Font.PLAIN)
    return Font(family, style, sizeSp.toInt().coerceAtLeast(1))
}

private fun drawArrowHead(
    graphics: java.awt.Graphics2D,
    tip: Point,
    tail: Point,
    head: ArrowHead,
    color: Color,
) {
    if (head == ArrowHead.None) return
    val angle = atan2((tip.y - tail.y).toDouble(), (tip.x - tail.x).toDouble())
    val size = 8.0
    fun point(distance: Double, offset: Double): Point {
        val cos = cos(angle)
        val sin = sin(angle)
        return Point(
            x = (tip.x - distance * cos + offset * -sin).toFloat(),
            y = (tip.y - distance * sin + offset * cos).toFloat(),
        )
    }
    graphics.color = color.toAwtColor()
    when (head) {
        ArrowHead.Triangle, ArrowHead.Diamond, ArrowHead.Circle -> {
            if (head == ArrowHead.Circle) {
                graphics.fill(Ellipse2D.Float((tip.x - 4f), (tip.y - 4f), 8f, 8f))
            } else {
                val path = Path2D.Float().apply {
                    moveTo(tip.x.toDouble(), tip.y.toDouble())
                    val left = point(size, -size / 2.0)
                    val right = point(size, size / 2.0)
                    lineTo(left.x.toDouble(), left.y.toDouble())
                    if (head == ArrowHead.Diamond) {
                        val back = point(size * 1.6, 0.0)
                        lineTo(back.x.toDouble(), back.y.toDouble())
                    }
                    lineTo(right.x.toDouble(), right.y.toDouble())
                    closePath()
                }
                graphics.fill(path)
            }
        }
        ArrowHead.OpenTriangle, ArrowHead.OpenDiamond, ArrowHead.OpenCircle, ArrowHead.Bar, ArrowHead.Cross -> {
            if (head == ArrowHead.OpenCircle) {
                graphics.draw(Ellipse2D.Float((tip.x - 4f), (tip.y - 4f), 8f, 8f))
            } else {
                val path = Path2D.Float().apply {
                    when (head) {
                        ArrowHead.Bar -> {
                            val left = point(0.0, -size / 2.0)
                            val right = point(0.0, size / 2.0)
                            moveTo(left.x.toDouble(), left.y.toDouble())
                            lineTo(right.x.toDouble(), right.y.toDouble())
                        }
                        ArrowHead.Cross -> {
                            val a = point(size / 2.0, -size / 2.0)
                            val b = point(-size / 2.0, size / 2.0)
                            val c = point(size / 2.0, size / 2.0)
                            val d = point(-size / 2.0, -size / 2.0)
                            moveTo(a.x.toDouble(), a.y.toDouble())
                            lineTo(b.x.toDouble(), b.y.toDouble())
                            moveTo(c.x.toDouble(), c.y.toDouble())
                            lineTo(d.x.toDouble(), d.y.toDouble())
                        }
                        ArrowHead.OpenDiamond -> {
                            val left = point(size / 2.0, -size / 3.0)
                            val back = point(size, 0.0)
                            val right = point(size / 2.0, size / 3.0)
                            moveTo(tip.x.toDouble(), tip.y.toDouble())
                            lineTo(left.x.toDouble(), left.y.toDouble())
                            lineTo(back.x.toDouble(), back.y.toDouble())
                            lineTo(right.x.toDouble(), right.y.toDouble())
                            closePath()
                        }
                        ArrowHead.OpenTriangle -> {
                            val left = point(size, -size / 2.0)
                            val right = point(size, size / 2.0)
                            moveTo(left.x.toDouble(), left.y.toDouble())
                            lineTo(tip.x.toDouble(), tip.y.toDouble())
                            lineTo(right.x.toDouble(), right.y.toDouble())
                        }
                        ArrowHead.None -> Unit
                    }
                }
                graphics.draw(path)
            }
        }
        ArrowHead.None -> Unit
    }
}

private fun encodePng(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().use { out ->
        ImageIO.write(image, "png", out)
        out.toByteArray()
    }

private fun encodeJpeg(image: BufferedImage, quality: Int): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val output = ByteArrayOutputStream()
    val stream = MemoryCacheImageOutputStream(output)
    try {
        writer.output = stream
        val params = JPEGImageWriteParam(null).apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = (quality.coerceIn(1, 100) / 100f)
        }
        writer.write(null, IIOImage(image, null, null), params)
        stream.flush()
        return output.toByteArray()
    } finally {
        writer.dispose()
        stream.close()
        output.close()
    }
}
