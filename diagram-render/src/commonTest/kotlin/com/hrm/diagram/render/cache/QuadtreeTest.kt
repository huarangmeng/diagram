package com.hrm.diagram.render.cache

import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
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
}
