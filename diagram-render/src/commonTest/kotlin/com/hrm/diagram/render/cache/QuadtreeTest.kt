package com.hrm.diagram.render.cache

import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuadtreeTest {

    private val world = Rect(Point.Zero, Size(1000f, 1000f))

    @Test
    fun query_returns_only_intersecting_entries() {
        val q = Quadtree<String>(world, bucketSize = 4, maxDepth = 6)
        q.insert(Rect.ltrb(10f, 10f, 50f, 50f), "a")
        q.insert(Rect.ltrb(800f, 800f, 850f, 850f), "b")
        q.insert(Rect.ltrb(400f, 400f, 600f, 600f), "c")

        val nearOrigin = q.query(Rect.ltrb(0f, 0f, 100f, 100f)).toSet()
        assertEquals(setOf("a"), nearOrigin)

        val center = q.query(Rect.ltrb(450f, 450f, 550f, 550f)).toSet()
        assertEquals(setOf("c"), center)

        val all = q.query(world).toSet()
        assertEquals(setOf("a", "b", "c"), all)
    }

    @Test
    fun subdivides_when_bucket_exceeded_and_still_returns_all() {
        val q = Quadtree<Int>(world, bucketSize = 2, maxDepth = 4)
        // 25 random points across the world; bucket 2 → forces multiple subdivisions.
        repeat(25) { i ->
            val x = (i * 41 % 980).toFloat()
            val y = (i * 73 % 980).toFloat()
            q.insert(Rect.ltrb(x, y, x + 5f, y + 5f), i)
        }
        assertEquals(25, q.size)
        // Bounding query MUST recover everything regardless of subdivision.
        assertEquals((0 until 25).toSet(), q.query(world).toSet())
    }

    @Test
    fun straddling_entries_stay_at_parent_and_are_still_found() {
        val q = Quadtree<String>(world, bucketSize = 1, maxDepth = 3)
        // Two corner entries to force subdivision.
        q.insert(Rect.ltrb(10f, 10f, 20f, 20f), "tl")
        q.insert(Rect.ltrb(900f, 900f, 950f, 950f), "br")
        // A straddler crossing the midline.
        q.insert(Rect.ltrb(400f, 400f, 600f, 600f), "mid")
        val all = q.query(world).toSet()
        assertEquals(setOf("tl", "br", "mid"), all)
    }

    @Test
    fun rejects_out_of_bounds_insert() {
        val q = Quadtree<String>(world)
        assertFailsWith<IllegalArgumentException> {
            q.insert(Rect.ltrb(-100f, -100f, -50f, -50f), "x")
        }
    }

    @Test
    fun clear_resets_state() {
        val q = Quadtree<String>(world)
        q.insert(Rect.ltrb(10f, 10f, 20f, 20f), "a")
        q.clear()
        assertEquals(0, q.size)
        assertTrue(q.query(world).isEmpty())
    }

    @Test
    fun draw_command_index_queries_visible_commands_only() {
        val near = DrawCommand.FillRect(Rect.ltrb(10f, 10f, 50f, 50f), Color.Black, z = 1)
        val far = DrawCommand.FillRect(Rect.ltrb(800f, 800f, 850f, 850f), Color.Black, z = 2)
        val index = DrawCommandIndex.from(listOf(near, far), world)

        assertEquals(listOf(near), index.query(Rect.ltrb(0f, 0f, 100f, 100f)))
        assertEquals(listOf(far), index.query(Rect.ltrb(790f, 790f, 860f, 860f)))
    }

    @Test
    fun draw_command_index_keeps_unbounded_groups_visible() {
        val group = DrawCommand.Group(
            children = listOf(DrawCommand.FillRect(Rect.ltrb(900f, 900f, 920f, 920f), Color.Black)),
            z = 10,
        )
        val near = DrawCommand.FillRect(Rect.ltrb(10f, 10f, 50f, 50f), Color.Black)
        val index = DrawCommandIndex.from(listOf(group, near), world)

        assertEquals(listOf(near, group), index.query(Rect.ltrb(0f, 0f, 100f, 100f)))
    }

    @Test
    fun draw_command_index_keeps_text_visible_until_measured_bounds_are_explicit() {
        val text = DrawCommand.DrawText(
            text = "wide text",
            origin = Point(900f, 900f),
            font = FontSpec(family = "sans-serif", sizeSp = 24f),
            color = Color.Black,
            z = 5,
        )
        val index = DrawCommandIndex.from(listOf(text), world)

        assertEquals(listOf(text), index.query(Rect.ltrb(0f, 0f, 100f, 100f)))
    }

    @Test
    fun draw_command_index_culls_text_when_measured_bounds_are_explicit() {
        val near = DrawCommand.DrawText(
            text = "near",
            origin = Point(20f, 20f),
            font = FontSpec(family = "sans-serif", sizeSp = 12f),
            color = Color.Black,
            measuredBounds = Rect.ltrb(20f, 10f, 60f, 30f),
        )
        val far = near.copy(
            text = "far",
            origin = Point(900f, 900f),
            measuredBounds = Rect.ltrb(900f, 890f, 940f, 910f),
        )
        val index = DrawCommandIndex.from(listOf(near, far), world)

        assertEquals(listOf(near), index.query(Rect.ltrb(0f, 0f, 100f, 100f)))
    }
}
