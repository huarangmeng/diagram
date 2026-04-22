package com.hrm.diagram.core.draw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DrawCommandTest {
    @Test
    fun rectGeometry() {
        val r = Rect.ltrb(10f, 20f, 110f, 70f)
        assertEquals(100f, r.size.width)
        assertEquals(50f, r.size.height)
        assertTrue(r.contains(Point(50f, 30f)))
    }

    @Test
    fun colorChannels() {
        val c = Color.argb(0x80, 0x12, 0x34, 0x56)
        assertEquals(0x80, c.a)
        assertEquals(0x12, c.r)
        assertEquals(0x34, c.g)
        assertEquals(0x56, c.b)
    }

    @Test
    fun drawCommandEqualityIsStructural() {
        val a = DrawCommand.FillRect(Rect.ltrb(0f, 0f, 10f, 10f), Color.Black)
        val b = DrawCommand.FillRect(Rect.ltrb(0f, 0f, 10f, 10f), Color.Black)
        assertEquals(a, b)
    }

    @Test
    fun strokeRejectsNegativeWidth() {
        assertFailsWith<IllegalArgumentException> { Stroke(width = -1f) }
    }

    @Test
    fun groupHoldsChildrenAndTransform() {
        val cmd = DrawCommand.Group(
            children = listOf(
                DrawCommand.FillRect(Rect.ltrb(0f, 0f, 1f, 1f), Color.Black),
            ),
            transform = Transform(translate = Point(5f, 5f)),
        )
        assertEquals(1, cmd.children.size)
        assertEquals(5f, cmd.transform.translate.x)
    }
}
