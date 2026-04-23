package com.hrm.diagram.core.text

import com.hrm.diagram.core.draw.FontSpec

/**
 * Backend-neutral text measurement port. The pipeline calls this **before** layout so node
 * sizes can grow to fit their labels (and so renderers don't have to guess where to place the
 * text afterwards).
 *
 * Implementations:
 * - [HeuristicTextMeasurer]: monospace approximation, used in tests and as the default when
 *   no platform measurer is injected. Good to ~10% accuracy.
 * - `ComposeTextMeasurerAdapter` (in `diagram-render`): wraps Compose's [TextMeasurer] for
 *   pixel-perfect measurements. Inject this when using the Compose backend.
 *
 * Contract: pure function — same `(text, font, maxWidth)` MUST return identical metrics.
 */
interface TextMeasurer {
    fun measure(text: String, font: FontSpec, maxWidth: Float? = null): TextMetrics
}

/**
 * Result of a text measurement.
 *
 * - [width] / [height]: the bounding box of the rendered glyphs (px @ 1x).
 * - [ascent]: distance from the top of the box to the baseline of the first line. Renderers
 *   use this to convert between top-left and baseline-anchored origins.
 * - [lineCount]: number of visual lines after wrapping.
 */
data class TextMetrics(
    val width: Float,
    val height: Float,
    val ascent: Float,
    val lineCount: Int = 1,
)

/**
 * Pure-Kotlin fallback: assumes monospace ~0.6em advance and 1.4em line height. Wraps on
 * `\n` and on word boundaries when [maxWidth] is set. Sufficient for tests and headless
 * exports; replace with [TextMeasurer] from the platform for real rendering.
 */
class HeuristicTextMeasurer(
    private val advanceRatio: Float = 0.58f,
    private val lineHeightRatio: Float = 1.4f,
    private val ascentRatio: Float = 1.05f,
) : TextMeasurer {
    override fun measure(text: String, font: FontSpec, maxWidth: Float?): TextMetrics {
        if (text.isEmpty()) {
            val h = font.sizeSp * lineHeightRatio
            return TextMetrics(width = 0f, height = h, ascent = font.sizeSp * ascentRatio, lineCount = 1)
        }
        val em = font.sizeSp
        val perChar = em * advanceRatio
        val rawLines = text.split('\n')
        val lines = ArrayList<String>(rawLines.size)
        for (line in rawLines) {
            if (maxWidth == null || maxWidth <= 0f || line.length * perChar <= maxWidth) {
                lines += line
                continue
            }
            // Word-break wrap; falls back to char-break when a single token is too wide.
            val charsPerLine = (maxWidth / perChar).toInt().coerceAtLeast(1)
            val words = line.split(' ')
            val cur = StringBuilder()
            for (w in words) {
                val candidate = if (cur.isEmpty()) w else "${cur} $w"
                if (candidate.length <= charsPerLine) {
                    cur.clear(); cur.append(candidate)
                } else {
                    if (cur.isNotEmpty()) { lines += cur.toString(); cur.clear() }
                    if (w.length <= charsPerLine) cur.append(w)
                    else {
                        var i = 0
                        while (i < w.length) {
                            val end = (i + charsPerLine).coerceAtMost(w.length)
                            lines += w.substring(i, end)
                            i = end
                        }
                    }
                }
            }
            if (cur.isNotEmpty()) lines += cur.toString()
        }
        val widest = lines.maxOf { it.length } * perChar
        val height = lines.size * em * lineHeightRatio
        return TextMetrics(
            width = widest,
            height = height,
            ascent = em * ascentRatio,
            lineCount = lines.size,
        )
    }
}
