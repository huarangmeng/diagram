package com.hrm.diagram.core.random

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RandomSourceTest {
    @Test
    fun sameSeedYieldsSameSequence() {
        val a = RandomSource(42L)
        val b = RandomSource(42L)
        repeat(10) {
            assertEquals(a.nextDouble(), b.nextDouble())
        }
    }

    @Test
    fun differentSeedsDiverge() {
        val a = RandomSource(1L)
        val b = RandomSource(2L)
        // Extremely unlikely to match for 10 draws.
        var anyDifferent = false
        repeat(10) {
            if (a.nextDouble() != b.nextDouble()) anyDifferent = true
        }
        assertTrue(anyDifferent)
    }

    @Test
    fun deriveProducesIndependentStream() {
        val parent = RandomSource(7L)
        val child = parent.derive(salt = 0xBEEFL)
        // Derived stream's first value should not equal parent's first value.
        assertNotEquals(parent.nextLong(), child.nextLong())
    }
}
