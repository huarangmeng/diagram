package com.hrm.diagram.bench

import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlinx.coroutines.runBlocking

/**
 * Synthetic streaming workload that mirrors the LLM emit pattern: a long source emitted in
 * many small chunks, then a single `finish()`.
 *
 * Timings include the whole workload: setup/prefill plus append, or every chunked append plus
 * finish/close. They are not isolated API-call latencies. `docs/testing.md` documents the relaxed
 * CI guards; the single-call performance targets remain in `docs/streaming.md` §4.
 */
internal object SessionWorkload {

    /** A 200-node Mermaid graph, emitted in [CHUNK_SIZE]-character chunks. */
    val SOURCE: String = buildString {
        appendLine("flowchart TD")
        repeat(200) { i ->
            appendLine("  n$i[\"Step $i: do something interesting and a bit longer\"]")
            if (i > 0) appendLine("  n${i - 1} --> n$i")
        }
    }

    const val CHUNK_SIZE: Int = 16

    /** Run a single full session: many `append` calls then `finish`. */
    fun runFullSession(): Unit = runBlocking {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            var i = 0
            while (i < SOURCE.length) {
                val end = (i + CHUNK_SIZE).coerceAtMost(SOURCE.length)
                s.append(SOURCE.substring(i, end))
                i = end
            }
            s.finish()
        } finally {
            s.close()
        }
    }

    /** Mid-session single append: pre-fill half the source, then append one more chunk. */
    fun runMidSessionAppend(): Unit = runBlocking {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append(SOURCE.substring(0, SOURCE.length / 2))
            s.append(SOURCE.substring(SOURCE.length / 2, SOURCE.length / 2 + CHUNK_SIZE))
        } finally {
            s.close()
        }
    }
}
