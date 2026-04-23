package com.hrm.diagram.bench

/**
 * Ad-hoc bench main: prints percentile tables for the streaming workload.
 * Invoke with `./gradlew :diagram-bench:runBench`.
 */
fun main() {
    println("== streaming session workload (${SessionWorkload.SOURCE.length} chars, " +
        "${SessionWorkload.CHUNK_SIZE}-byte chunks) ==")

    val appendResult = bench(name = "append (mid-session)", warmupIterations = 100, measurementIterations = 500) {
        SessionWorkload.runMidSessionAppend()
    }
    println(appendResult)

    val finishResult = bench(name = "full session (append* + finish)", warmupIterations = 5, measurementIterations = 30) {
        SessionWorkload.runFullSession()
    }
    println(finishResult)
}
