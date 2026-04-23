package com.hrm.diagram.bench

import kotlin.math.max
import kotlin.math.min

/**
 * Tiny self-contained micro-benchmark runner — no JMH dependency.
 *
 * Usage: pre-warm JIT with [warmupIterations] runs, then time [measurementIterations] runs and
 * compute basic latency stats. We deliberately avoid JMH so that:
 * - `:diagram-bench:jvmTest` can fail-on-budget in normal CI in <1 s.
 * - Ad-hoc runs (`./gradlew :diagram-bench:runBench`) print percentiles without extra plugins.
 *
 * Returns nanoseconds. Perform repeated work inside [block] to keep per-call noise low.
 */
internal fun bench(
    name: String,
    warmupIterations: Int = 50,
    measurementIterations: Int = 200,
    block: () -> Unit,
): BenchResult {
    repeat(warmupIterations) { block() }
    val samples = LongArray(measurementIterations)
    for (i in 0 until measurementIterations) {
        val t0 = System.nanoTime()
        block()
        samples[i] = System.nanoTime() - t0
    }
    return BenchResult.of(name, samples)
}

internal data class BenchResult(
    val name: String,
    val count: Int,
    val minNs: Long,
    val maxNs: Long,
    val meanNs: Double,
    val p50Ns: Long,
    val p95Ns: Long,
    val p99Ns: Long,
) {
    val p50Ms: Double get() = p50Ns / 1_000_000.0
    val p95Ms: Double get() = p95Ns / 1_000_000.0
    val p99Ms: Double get() = p99Ns / 1_000_000.0

    override fun toString(): String =
        "$name  count=$count  min=${ms(minNs)}  mean=${"%.3f".format(meanNs / 1_000_000)}ms  " +
            "p50=${ms(p50Ns)}  p95=${ms(p95Ns)}  p99=${ms(p99Ns)}  max=${ms(maxNs)}"

    companion object {
        internal fun of(name: String, samples: LongArray): BenchResult {
            require(samples.isNotEmpty())
            val sorted = samples.copyOf().also { it.sort() }
            var sum = 0L; var lo = Long.MAX_VALUE; var hi = Long.MIN_VALUE
            for (s in samples) { sum += s; lo = min(lo, s); hi = max(hi, s) }
            return BenchResult(
                name = name,
                count = samples.size,
                minNs = lo,
                maxNs = hi,
                meanNs = sum.toDouble() / samples.size,
                p50Ns = pct(sorted, 0.50),
                p95Ns = pct(sorted, 0.95),
                p99Ns = pct(sorted, 0.99),
            )
        }

        private fun pct(sortedAsc: LongArray, q: Double): Long {
            val idx = ((sortedAsc.size - 1) * q).toInt().coerceIn(0, sortedAsc.size - 1)
            return sortedAsc[idx]
        }

        private fun ms(ns: Long): String = "%.3fms".format(ns / 1_000_000.0)
    }
}
