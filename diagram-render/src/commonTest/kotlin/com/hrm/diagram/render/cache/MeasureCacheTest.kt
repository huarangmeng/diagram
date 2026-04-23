package com.hrm.diagram.render.cache

import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureCacheTest {

    private val font = FontSpec(family = "sans-serif", sizeSp = 14f)

    @Test
    fun get_or_put_returns_cached_value_on_second_call() {
        val cache = MeasureCache<Size>(maxEntries = 4)
        var called = 0
        val key = MeasureKey("hello", font)
        val first = cache.getOrPut(key) { called++; Size(40f, 12f) }
        val second = cache.getOrPut(key) { called++; Size(99f, 99f) }
        assertEquals(Size(40f, 12f), first)
        assertEquals(first, second)
        assertEquals(1, called)
        assertEquals(1L, cache.hits)
        assertEquals(1L, cache.misses)
    }

    @Test
    fun lru_evicts_least_recently_used_entry() {
        val cache = MeasureCache<Size>(maxEntries = 2)
        val k1 = MeasureKey("a", font)
        val k2 = MeasureKey("b", font)
        val k3 = MeasureKey("c", font)
        cache.put(k1, Size(1f, 1f))
        cache.put(k2, Size(2f, 2f))
        // Touch k1 to mark it MRU; k2 becomes LRU.
        cache.get(k1)
        cache.put(k3, Size(3f, 3f))
        assertEquals(2, cache.size)
        assertNull(cache.get(k2), "k2 must have been evicted")
        assertEquals(Size(1f, 1f), cache.get(k1))
        assertEquals(Size(3f, 3f), cache.get(k3))
    }

    @Test
    fun put_overwrites_and_does_not_grow_size() {
        val cache = MeasureCache<Size>(maxEntries = 4)
        val k = MeasureKey("x", font)
        cache.put(k, Size(1f, 1f))
        cache.put(k, Size(2f, 2f))
        assertEquals(1, cache.size)
        assertEquals(Size(2f, 2f), cache.get(k))
    }

    @Test
    fun keys_with_different_max_width_are_distinct() {
        val cache = MeasureCache<Size>()
        val a = MeasureKey("foo", font, maxWidth = 100f)
        val b = MeasureKey("foo", font, maxWidth = 200f)
        val c = MeasureKey("foo", font, maxWidth = null)
        cache.put(a, Size(80f, 12f))
        cache.put(b, Size(180f, 12f))
        cache.put(c, Size(40f, 12f))
        assertEquals(3, cache.size)
        assertEquals(Size(80f, 12f), cache.get(a))
        assertEquals(Size(180f, 12f), cache.get(b))
        assertEquals(Size(40f, 12f), cache.get(c))
    }

    @Test
    fun clear_resets_counters() {
        val cache = MeasureCache<Size>()
        val k = MeasureKey("x", font)
        cache.put(k, Size(1f, 1f)); cache.get(k); cache.get(MeasureKey("y", font))
        assertTrue(cache.hits > 0); assertTrue(cache.misses > 0)
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.hits)
        assertEquals(0L, cache.misses)
    }

    @Test
    fun rejects_zero_max_entries() {
        assertFailsWith<IllegalArgumentException> { MeasureCache<Size>(maxEntries = 0) }
    }

    @Test
    fun heavy_churn_stays_within_cap() {
        val cache = MeasureCache<Size>(maxEntries = 10)
        repeat(10_000) { i ->
            cache.put(MeasureKey("k$i", font), Size(i.toFloat(), 1f))
        }
        assertEquals(10, cache.size)
    }
}
