package com.hrm.diagram.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MicroBenchTest {

    @Test
    fun bench_collects_samples_and_reports_percentiles() {
        var x = 0
        val r = bench(name = "noop", warmupIterations = 5, measurementIterations = 50) { x++ }
        assertEquals(50, r.count)
        assertTrue(r.minNs >= 0)
        assertTrue(r.maxNs >= r.minNs)
        assertTrue(r.p50Ns in r.minNs..r.maxNs)
        assertTrue(r.p95Ns >= r.p50Ns)
        assertTrue(r.p99Ns >= r.p95Ns)
        assertTrue(x >= 55, "block must run warmup + measurement times")
    }
}
