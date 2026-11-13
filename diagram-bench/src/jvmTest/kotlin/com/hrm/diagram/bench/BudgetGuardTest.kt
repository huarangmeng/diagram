package com.hrm.diagram.bench

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Coarse regression guard for streaming workloads on shared CI runners.
 *
 * Workloads include session setup/prefill or every chunked append plus finish, so their totals
 * are not single-call append/finish latencies. Relaxed thresholds allow scheduling, JIT and GC
 * noise while retaining a guard against large regressions. `runBench` reports the real numbers.
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
    fun full_session_p95_within_budget() {
        val r = bench(name = "full-session-guard", warmupIterations = 10, measurementIterations = 30) {
            SessionWorkload.runFullSession()
        }
        println(r)
        assertTrue(r.p95Ms < FULL_SESSION_P95_BUDGET_MS, "full session p95=${r.p95Ms}ms exceeded ${FULL_SESSION_P95_BUDGET_MS}ms")
    }

    private companion object {
        const val APPEND_P95_BUDGET_MS = 250.0
        const val FULL_SESSION_P95_BUDGET_MS = 1_000.0
    }
}
