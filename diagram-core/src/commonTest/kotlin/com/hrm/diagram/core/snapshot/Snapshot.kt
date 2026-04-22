package com.hrm.diagram.core.snapshot

import kotlin.test.assertEquals

/**
 * Phase-0 string snapshot helper.
 * Compares [actual] against [expected] line-by-line so failures pinpoint the offending row.
 *
 * Real golden-file infra (read/update from `commonTest/resources/snapshots/<name>.txt`)
 * arrives in Phase 7 once we wire each platform's resource loader.
 */
object Snapshot {
    fun assertEquals(name: String, expected: String, actual: String) {
        if (expected == actual) return
        val expLines = expected.split('\n')
        val actLines = actual.split('\n')
        val limit = maxOf(expLines.size, actLines.size)
        val diff = buildString {
            append("Snapshot '").append(name).append("' mismatch:\n")
            for (i in 0 until limit) {
                val e = expLines.getOrNull(i)
                val a = actLines.getOrNull(i)
                if (e != a) {
                    append("  L").append(i + 1).append(" expected: ").append(e ?: "<none>").append('\n')
                    append("        actual: ").append(a ?: "<none>").append('\n')
                }
            }
        }
        // Delegate to kotlin.test for nice IDE diffing as well.
        assertEquals(expected, actual, diff)
    }
}
