package com.hrm.diagram.bench

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CI red-line for the streaming budget in `docs/streaming.md` §4.
 *
 * These thresholds intentionally bake in a generous safety margin (5x the spec target) so that
 * shared CI runners don't false-fail. The ad-hoc `runBench` task surfaces the real numbers; this
 * test only catches order-of-magnitude regressions.
 */
class BudgetGuardTest {

    @Test
    fun append_p95_within_budget() {
        val r = bench(name = "append-guard", warmupIterations = 30, measurementIterations = 100) {
            SessionWorkload.runMidSessionAppend()
        }
        println(r)
        assertTrue(r.p95Ms < APPEND_P95_BUDGET_MS, "append p95=${r.p95Ms}ms exceeded ${APPEND_P95_BUDGET_MS}ms")
    }

    @Test
    fun finish_p95_within_budget() {
        val r = bench(name = "finish-guard", warmupIterations = 3, measurementIterations = 15) {
            SessionWorkload.runFullSession()
        }
        println(r)
        // Spec target: p95 < 50ms for the full chunked session. 5x guard.
        assertTrue(r.p95Ms < FINISH_P95_BUDGET_MS, "finish p95=${r.p95Ms}ms exceeded ${FINISH_P95_BUDGET_MS}ms")
    }

    private companion object {
        const val APPEND_P95_BUDGET_MS = 80.0
        const val FINISH_P95_BUDGET_MS = 250.0
    }
}
