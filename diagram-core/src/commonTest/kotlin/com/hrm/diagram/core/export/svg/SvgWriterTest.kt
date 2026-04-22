package com.hrm.diagram.core.export.svg

import com.hrm.diagram.core.draw.ArrowStyle
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.Transform
import com.hrm.diagram.core.snapshot.Snapshot
import kotlin.test.Test

class SvgWriterTest {

    private val canvas = Size(200f, 100f)

    @Test
    fun writesEmptyDocument() {
        val svg = SvgWriter(canvas, background = null).write(emptyList())
        Snapshot.assertEquals(
            name = "empty",
            expected = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
</svg>""",
            actual = svg,
        )
    }

    @Test
    fun fillRectWithBackground() {
        val svg = SvgWriter(canvas, background = Color.White).write(listOf(
            DrawCommand.FillRect(Rect.ltrb(10f, 20f, 110f, 70f), Color.Black, corner = 4f),
        ))
        Snapshot.assertEquals(
            name = "fillRect",
            expected = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
  <rect width="100%" height="100%" fill="#ffffff"/>
  <rect x="10" y="20" width="100" height="50" rx="4" ry="4" fill="#000000"/>
</svg>""",
            actual = svg,
        )
    }

    @Test
    fun strokeRectAndPath() {
        val svg = SvgWriter(canvas).write(listOf(
            DrawCommand.StrokeRect(
                rect = Rect.ltrb(0f, 0f, 50f, 50f),
                stroke = Stroke(width = 2f),
                color = Color.argb(0xFF, 0x12, 0x34, 0x56),
            ),
            DrawCommand.StrokePath(
                path = PathCmd(listOf(
                    PathOp.MoveTo(Point(0f, 0f)),
                    PathOp.LineTo(Point(10f, 10f)),
                    PathOp.QuadTo(Point(20f, 20f), Point(30f, 0f)),
                    PathOp.Close,
                )),
                stroke = Stroke(width = 1f, dash = listOf(2f, 3f)),
                color = Color.Black,
            ),
        ))
        Snapshot.assertEquals(
            name = "strokes",
            expected = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
  <rect x="0" y="0" width="50" height="50" fill="none" stroke="#123456" stroke-width="2"/>
  <path d="M 0 0 L 10 10 Q 20 20 30 0 Z" fill="none" stroke="#000000" stroke-width="1" stroke-dasharray="2,3"/>
</svg>""",
            actual = svg,
        )
    }

    @Test
    fun textAndGroupTransform() {
        val svg = SvgWriter(canvas).write(listOf(
            DrawCommand.Group(
                transform = Transform(translate = Point(5f, 7f), scale = 2f),
                children = listOf(
                    DrawCommand.DrawText(
                        text = "hi <world> & friends",
                        origin = Point(0f, 12f),
                        font = FontSpec(family = "sans-serif", sizeSp = 14f, weight = 600),
                        color = Color.Black,
                    ),
                ),
            ),
        ))
        Snapshot.assertEquals(
            name = "text",
            expected = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
  <g transform="translate(5, 7) scale(2)">
    <text x="0" y="12" font-family="sans-serif" font-size="14" font-weight="600" fill="#000000">hi &lt;world&gt; &amp; friends</text>
  </g>
</svg>""",
            actual = svg,
        )
    }

    @Test
    fun zOrderingStableAndAscending() {
        val svg = SvgWriter(canvas).write(listOf(
            DrawCommand.FillRect(Rect.ltrb(0f, 0f, 1f, 1f), Color.argb(0xFF, 0xAA, 0xAA, 0xAA), z = 5),
            DrawCommand.FillRect(Rect.ltrb(2f, 2f, 3f, 3f), Color.argb(0xFF, 0xBB, 0xBB, 0xBB), z = 1),
            DrawCommand.FillRect(Rect.ltrb(4f, 4f, 5f, 5f), Color.argb(0xFF, 0xCC, 0xCC, 0xCC), z = 1),
        ))
        // Order should be: BB (z=1, idx 1), CC (z=1, idx 2), AA (z=5, idx 0)
        val bbIdx = svg.indexOf("#bbbbbb")
        val ccIdx = svg.indexOf("#cccccc")
        val aaIdx = svg.indexOf("#aaaaaa")
        check(bbIdx in 0 until ccIdx) { "expected BB before CC, got $svg" }
        check(ccIdx in 0 until aaIdx) { "expected CC before AA, got $svg" }
    }

    @Test
    fun arrowEmitsLineAndTriangle() {
        val svg = SvgWriter(canvas).write(listOf(
            DrawCommand.DrawArrow(
                from = Point(0f, 50f),
                to = Point(100f, 50f),
                style = ArrowStyle(color = Color.Black, stroke = Stroke(1f)),
            ),
        ))
        check("<line " in svg) { "arrow should emit a <line>: $svg" }
        check("<polygon " in svg) { "arrow should emit a triangle <polygon>: $svg" }
    }

    @Test
    fun hyperlinkWrapsTransparentRect() {
        val svg = SvgWriter(canvas).write(listOf(
            DrawCommand.Hyperlink(href = "https://example.com/?q=1&x=2", rect = Rect.ltrb(0f, 0f, 10f, 10f)),
        ))
        check("""<a href="https://example.com/?q=1&amp;x=2">""" in svg) { svg }
        check("fill=\"transparent\"" in svg) { svg }
    }
}
