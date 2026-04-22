package com.hrm.diagram.core.export.svg

import com.hrm.diagram.core.draw.ArrowHead
import com.hrm.diagram.core.draw.ArrowStyle
import com.hrm.diagram.core.draw.Cap
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Join
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.Transform
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase-0 SVG writer.
 *
 * Pure function: `(canvas, background, commands)` → SVG document string.
 * No platform-specific deps (pure commonMain). Output is **deterministic** so it can
 * be string-snapshotted across JVM/JS/Wasm/Native. See docs/draw-command.md §3.
 *
 * Limitations (to be lifted in later phases):
 *  - DrawIcon is rendered as a stub `<rect>` + `<title>name</title>`.
 *  - Markdown / HTML rich labels are not yet parsed; callers must lower them to plain DrawText.
 *  - Hyperlink wraps a transparent rect — does not nest other commands yet.
 */
class SvgWriter(
    private val canvas: Size,
    private val background: Color? = null,
) {
    fun write(commands: List<DrawCommand>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<svg xmlns="http://www.w3.org/2000/svg" """)
        append("""width="""").append(fmt(canvas.width)).append("\" ")
        append("""height="""").append(fmt(canvas.height)).append("\" ")
        append("""viewBox="0 0 """).append(fmt(canvas.width)).append(' ').append(fmt(canvas.height)).append('"')
        append('>').append('\n')
        if (background != null && background.a != 0) {
            append("""  <rect width="100%" height="100%" fill="""")
            append(colorHex(background)).append('"')
            opacityAttr(background)?.let { append(' ').append(it) }
            append("/>\n")
        }
        writeAll(commands, indent = 1)
        append("</svg>")
    }

    private fun StringBuilder.writeAll(commands: List<DrawCommand>, indent: Int) {
        // Stable z-ordering: low z first, ties preserve list order.
        val ordered = commands.withIndex().sortedWith(
            compareBy({ it.value.z }, { it.index }),
        ).map { it.value }
        for (cmd in ordered) writeCmd(cmd, indent)
    }

    private fun StringBuilder.writeCmd(cmd: DrawCommand, indent: Int) {
        val pad = "  ".repeat(indent)
        when (cmd) {
            is DrawCommand.FillRect -> {
                append(pad).append("<rect ")
                rectAttrs(cmd.rect, cmd.corner)
                append(""" fill="""").append(colorHex(cmd.color)).append('"')
                opacityAttr(cmd.color, attr = "fill-opacity")?.let { append(' ').append(it) }
                append("/>\n")
            }
            is DrawCommand.StrokeRect -> {
                append(pad).append("<rect ")
                rectAttrs(cmd.rect, cmd.corner)
                append(""" fill="none" stroke="""").append(colorHex(cmd.color)).append('"')
                opacityAttr(cmd.color, attr = "stroke-opacity")?.let { append(' ').append(it) }
                append(' ').append(strokeAttrs(cmd.stroke))
                append("/>\n")
            }
            is DrawCommand.FillPath -> {
                append(pad).append("""<path d="""")
                appendPath(cmd.path)
                append(""""""")
                append(""" fill="""").append(colorHex(cmd.color)).append('"')
                opacityAttr(cmd.color, attr = "fill-opacity")?.let { append(' ').append(it) }
                append("/>\n")
            }
            is DrawCommand.StrokePath -> {
                append(pad).append("""<path d="""")
                appendPath(cmd.path)
                append(""""""")
                append(""" fill="none" stroke="""").append(colorHex(cmd.color)).append('"')
                opacityAttr(cmd.color, attr = "stroke-opacity")?.let { append(' ').append(it) }
                append(' ').append(strokeAttrs(cmd.stroke))
                append("/>\n")
            }
            is DrawCommand.DrawText -> {
                append(pad).append("<text ")
                append("""x="""").append(fmt(cmd.origin.x)).append("\" ")
                append("""y="""").append(fmt(cmd.origin.y)).append("\" ")
                append(fontAttrs(cmd.font))
                append(""" fill="""").append(colorHex(cmd.color)).append('"')
                opacityAttr(cmd.color, attr = "fill-opacity")?.let { append(' ').append(it) }
                val mw = cmd.maxWidth
                if (mw != null) {
                    append(""" textLength="""").append(fmt(mw)).append("\" lengthAdjust=\"spacingAndGlyphs\"")
                }
                append('>').append(escapeXml(cmd.text)).append("</text>\n")
            }
            is DrawCommand.DrawArrow -> {
                writeArrow(cmd.from, cmd.to, cmd.style, pad)
            }
            is DrawCommand.DrawIcon -> {
                append(pad).append("<g class=\"icon\">")
                append("<title>").append(escapeXml(cmd.name)).append("</title>")
                append("<rect ")
                rectAttrs(cmd.rect, corner = 0f)
                append(""" fill="none" stroke="#888" stroke-dasharray="2,2"/>""")
                append("</g>\n")
            }
            is DrawCommand.Group -> {
                append(pad).append("<g")
                transformAttr(cmd.transform)?.let { append(' ').append(it) }
                append(">\n")
                writeAll(cmd.children, indent + 1)
                append(pad).append("</g>\n")
            }
            is DrawCommand.Clip -> {
                val clipId = "clip" + cmd.hashCode().toUInt().toString(16)
                append(pad).append("<defs><clipPath id=\"").append(clipId).append("\"><rect ")
                rectAttrs(cmd.rect, corner = 0f)
                append("/></clipPath></defs>\n")
                append(pad).append("<g clip-path=\"url(#").append(clipId).append(")\">\n")
                writeAll(cmd.children, indent + 1)
                append(pad).append("</g>\n")
            }
            is DrawCommand.Hyperlink -> {
                append(pad).append("<a href=\"").append(escapeAttr(cmd.href)).append("\"><rect ")
                rectAttrs(cmd.rect, corner = 0f)
                append(""" fill="transparent"/></a>""").append('\n')
            }
        }
    }

    private fun StringBuilder.writeArrow(from: Point, to: Point, style: ArrowStyle, pad: String) {
        append(pad).append("<g class=\"arrow\">\n")
        append(pad).append("  <line ")
        append("""x1="""").append(fmt(from.x)).append("\" ")
        append("""y1="""").append(fmt(from.y)).append("\" ")
        append("""x2="""").append(fmt(to.x)).append("\" ")
        append("""y2="""").append(fmt(to.y)).append("\" ")
        append("""stroke="""").append(colorHex(style.color)).append('"')
        opacityAttr(style.color, attr = "stroke-opacity")?.let { append(' ').append(it) }
        append(' ').append(strokeAttrs(style.stroke))
        append("/>\n")
        appendArrowHead(from, to, style.head, atEnd = true, style, pad)
        appendArrowHead(to, from, style.tail, atEnd = true, style, pad)
        append(pad).append("</g>\n")
    }

    private fun StringBuilder.appendArrowHead(
        baseAnchor: Point,
        tip: Point,
        head: ArrowHead,
        atEnd: Boolean,
        style: ArrowStyle,
        pad: String,
    ) {
        if (head == ArrowHead.None) return
        // base = anchor for line direction; tip = where the head sits.
        val (bx, by, tx, ty) = if (atEnd) {
            floatArrayOf(baseAnchor.x, baseAnchor.y, tip.x, tip.y)
        } else {
            floatArrayOf(tip.x, tip.y, baseAnchor.x, baseAnchor.y)
        }.let { arrayOf(it[0], it[1], it[2], it[3]) }

        val angle = atan2((ty - by).toDouble(), (tx - bx).toDouble())
        val size = 8f * (style.stroke.width.coerceAtLeast(1f))
        val left = rotateOffset(angle, -size, -size / 2f)
        val right = rotateOffset(angle, -size, size / 2f)
        val p1x = tx + left.first
        val p1y = ty + left.second
        val p2x = tx + right.first
        val p2y = ty + right.second
        when (head) {
            ArrowHead.Triangle, ArrowHead.OpenTriangle -> {
                val fill = if (head == ArrowHead.Triangle) colorHex(style.color) else "none"
                append(pad).append("  <polygon points=\"")
                append(fmt(tx)).append(',').append(fmt(ty)).append(' ')
                append(fmt(p1x.toFloat())).append(',').append(fmt(p1y.toFloat())).append(' ')
                append(fmt(p2x.toFloat())).append(',').append(fmt(p2y.toFloat()))
                append("\" fill=\"").append(fill).append("\" stroke=\"").append(colorHex(style.color)).append("\"/>\n")
            }
            ArrowHead.Diamond, ArrowHead.OpenDiamond -> {
                val fill = if (head == ArrowHead.Diamond) colorHex(style.color) else "none"
                val far = rotateOffset(angle, -size, 0f)
                val fx = tx + far.first
                val fy = ty + far.second
                val side1 = rotateOffset(angle, -size / 2f, -size / 3f)
                val side2 = rotateOffset(angle, -size / 2f, size / 3f)
                append(pad).append("  <polygon points=\"")
                append(fmt(tx)).append(',').append(fmt(ty)).append(' ')
                append(fmt((tx + side1.first).toFloat())).append(',').append(fmt((ty + side1.second).toFloat())).append(' ')
                append(fmt(fx.toFloat())).append(',').append(fmt(fy.toFloat())).append(' ')
                append(fmt((tx + side2.first).toFloat())).append(',').append(fmt((ty + side2.second).toFloat()))
                append("\" fill=\"").append(fill).append("\" stroke=\"").append(colorHex(style.color)).append("\"/>\n")
            }
            ArrowHead.Circle, ArrowHead.OpenCircle -> {
                val fill = if (head == ArrowHead.Circle) colorHex(style.color) else "none"
                val centerOff = rotateOffset(angle, -size / 2f, 0f)
                val cx = tx + centerOff.first
                val cy = ty + centerOff.second
                append(pad).append("  <circle cx=\"").append(fmt(cx.toFloat())).append("\" cy=\"").append(fmt(cy.toFloat()))
                append("\" r=\"").append(fmt(size / 2f)).append("\" fill=\"").append(fill).append("\" stroke=\"")
                append(colorHex(style.color)).append("\"/>\n")
            }
            ArrowHead.Bar -> {
                val left2 = rotateOffset(angle, 0f, -size / 2f)
                val right2 = rotateOffset(angle, 0f, size / 2f)
                append(pad).append("  <line x1=\"").append(fmt((tx + left2.first).toFloat())).append("\" y1=\"")
                append(fmt((ty + left2.second).toFloat())).append("\" x2=\"")
                append(fmt((tx + right2.first).toFloat())).append("\" y2=\"")
                append(fmt((ty + right2.second).toFloat()))
                append("\" stroke=\"").append(colorHex(style.color)).append("\" stroke-width=\"")
                append(fmt(style.stroke.width)).append("\"/>\n")
            }
            ArrowHead.Cross -> {
                // X-mark made of two crossed lines.
                val a1 = rotateOffset(angle, -size / 2f, -size / 2f)
                val a2 = rotateOffset(angle,  size / 2f,  size / 2f)
                val b1 = rotateOffset(angle, -size / 2f,  size / 2f)
                val b2 = rotateOffset(angle,  size / 2f, -size / 2f)
                append(pad).append("  <line x1=\"").append(fmt((tx + a1.first).toFloat())).append("\" y1=\"")
                append(fmt((ty + a1.second).toFloat())).append("\" x2=\"")
                append(fmt((tx + a2.first).toFloat())).append("\" y2=\"")
                append(fmt((ty + a2.second).toFloat()))
                append("\" stroke=\"").append(colorHex(style.color)).append("\"/>\n")
                append(pad).append("  <line x1=\"").append(fmt((tx + b1.first).toFloat())).append("\" y1=\"")
                append(fmt((ty + b1.second).toFloat())).append("\" x2=\"")
                append(fmt((tx + b2.first).toFloat())).append("\" y2=\"")
                append(fmt((ty + b2.second).toFloat()))
                append("\" stroke=\"").append(colorHex(style.color)).append("\"/>\n")
            }
            ArrowHead.None -> Unit
        }
    }

    private fun rotateOffset(angle: Double, x: Float, y: Float): Pair<Double, Double> {
        val ca = cos(angle); val sa = sin(angle)
        return (x * ca - y * sa) to (x * sa + y * ca)
    }

    private fun StringBuilder.rectAttrs(r: Rect, corner: Float) {
        append("""x="""").append(fmt(r.origin.x)).append("\" ")
        append("""y="""").append(fmt(r.origin.y)).append("\" ")
        append("""width="""").append(fmt(r.size.width)).append("\" ")
        append("""height="""").append(fmt(r.size.height)).append('"')
        if (corner > 0f) {
            append(""" rx="""").append(fmt(corner)).append("\" ")
            append("""ry="""").append(fmt(corner)).append('"')
        }
    }

    private fun StringBuilder.appendPath(p: PathCmd) {
        var first = true
        for (op in p.ops) {
            if (!first) append(' ')
            first = false
            when (op) {
                is PathOp.MoveTo -> append('M').append(' ').append(fmt(op.p.x)).append(' ').append(fmt(op.p.y))
                is PathOp.LineTo -> append('L').append(' ').append(fmt(op.p.x)).append(' ').append(fmt(op.p.y))
                is PathOp.QuadTo -> append('Q').append(' ').append(fmt(op.ctrl.x)).append(' ').append(fmt(op.ctrl.y))
                    .append(' ').append(fmt(op.end.x)).append(' ').append(fmt(op.end.y))
                is PathOp.CubicTo -> append('C').append(' ').append(fmt(op.c1.x)).append(' ').append(fmt(op.c1.y))
                    .append(' ').append(fmt(op.c2.x)).append(' ').append(fmt(op.c2.y))
                    .append(' ').append(fmt(op.end.x)).append(' ').append(fmt(op.end.y))
                PathOp.Close -> append('Z')
            }
        }
    }

    private fun strokeAttrs(s: Stroke): String = buildString {
        append("""stroke-width="""").append(fmt(s.width)).append('"')
        when (s.cap) { Cap.Butt -> Unit; Cap.Round -> append(" stroke-linecap=\"round\""); Cap.Square -> append(" stroke-linecap=\"square\"") }
        when (s.join) { Join.Miter -> Unit; Join.Round -> append(" stroke-linejoin=\"round\""); Join.Bevel -> append(" stroke-linejoin=\"bevel\"") }
        val dash = s.dash
        if (!dash.isNullOrEmpty()) {
            append(" stroke-dasharray=\"")
            dash.forEachIndexed { i, v -> if (i > 0) append(','); append(fmt(v)) }
            append('"')
        }
    }

    private fun fontAttrs(f: FontSpec): String = buildString {
        append("""font-family="""").append(escapeAttr(f.family)).append('"')
        append(" font-size=\"").append(fmt(f.sizeSp)).append('"')
        if (f.weight != 400) append(" font-weight=\"").append(f.weight).append('"')
        if (f.italic) append(" font-style=\"italic\"")
    }

    private fun transformAttr(t: Transform): String? {
        if (t.isIdentity) return null
        val parts = buildList {
            if (t.translate.x != 0f || t.translate.y != 0f) {
                add("translate(${fmt(t.translate.x)}, ${fmt(t.translate.y)})")
            }
            if (t.rotateDeg != 0f) add("rotate(${fmt(t.rotateDeg)})")
            if (t.scale != 1f) add("scale(${fmt(t.scale)})")
        }
        return """transform="${parts.joinToString(" ")}""""
    }

    private fun colorHex(c: Color): String {
        val r = c.r; val g = c.g; val b = c.b
        return buildString(7) {
            append('#')
            append(hex2(r)); append(hex2(g)); append(hex2(b))
        }
    }

    private fun opacityAttr(c: Color, attr: String = "opacity"): String? {
        if (c.a == 0xFF) return null
        val opacity = c.a / 255.0f
        return """$attr="${fmt(opacity)}""""
    }

    private fun hex2(v: Int): String {
        val s = (v and 0xFF).toString(16)
        return if (s.length == 1) "0$s" else s
    }
}
