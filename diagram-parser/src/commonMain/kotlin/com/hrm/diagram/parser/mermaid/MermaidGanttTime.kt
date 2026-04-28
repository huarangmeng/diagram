package com.hrm.diagram.parser.mermaid

import kotlin.math.floor

/**
 * Minimal date/time + duration helpers for Mermaid gantt.
 *
 * IMPORTANT:
 * - commonMain compatible (no java.time).
 * - Focused subset for Phase 2: YYYY-MM-DD, YYYY, HH:mm, X (unix seconds), x (unix ms).
 * - Calendar math is Gregorian proleptic; timezone treated as UTC.
 */
internal object MermaidGanttTime {
    const val MS_PER_DAY: Long = 86_400_000L

    data class DateTime(val epochMs: Long)

    data class Duration(val millis: Long, val unitIsDayLike: Boolean, val dayLikeCount: Int)

    fun parseDateTime(text: String, format: String): DateTime? {
        val s = text.trim()
        if (s.isEmpty()) return null
        if (format == "X") return s.toDoubleOrNull()?.let { DateTime((it * 1000.0).toLong()) }
        if (format == "x") return s.toDoubleOrNull()?.let { DateTime(it.toLong()) }

        // Very small formatter: supports tokens YYYY, MM, DD, HH, mm, ss with separators.
        var i = 0
        var j = 0
        var year = 1970
        var month = 1
        var day = 1
        var hour = 0
        var minute = 0
        var second = 0

        fun readInt(len: Int): Int? {
            if (i + len > s.length) return null
            val sub = s.substring(i, i + len)
            val v = sub.toIntOrNull() ?: return null
            i += len
            return v
        }

        while (j < format.length) {
            when {
                format.startsWith("YYYY", j) -> {
                    year = readInt(4) ?: return null
                    j += 4
                }
                format.startsWith("MM", j) -> {
                    month = readInt(2) ?: return null
                    j += 2
                }
                format.startsWith("DD", j) -> {
                    day = readInt(2) ?: return null
                    j += 2
                }
                format.startsWith("HH", j) -> {
                    hour = readInt(2) ?: return null
                    j += 2
                }
                format.startsWith("mm", j) -> {
                    minute = readInt(2) ?: return null
                    j += 2
                }
                format.startsWith("ss", j) -> {
                    second = readInt(2) ?: return null
                    j += 2
                }
                else -> {
                    // Literal separator must match (or we skip whitespace differences).
                    val fc = format[j]
                    if (fc.isWhitespace()) {
                        while (i < s.length && s[i].isWhitespace()) i++
                        j++
                    } else {
                        // Skip any whitespace around separators in the input.
                        while (i < s.length && s[i].isWhitespace()) i++
                        if (i >= s.length) return null
                        val sc = s[i]
                        if (sc != fc) return null
                        i++; j++
                        while (i < s.length && s[i].isWhitespace()) i++
                    }
                }
            }
        }
        // ignore trailing spaces
        while (i < s.length && s[i].isWhitespace()) i++
        if (i != s.length) return null

        val epochDay = civilToEpochDay(year, month, day)
        val ms = epochDay * MS_PER_DAY + hour * 3_600_000L + minute * 60_000L + second * 1000L
        return DateTime(ms)
    }

    fun parseDuration(text: String): Duration? {
        val s = text.trim().replace(" ", "")
        if (s.isEmpty()) return null
        // Match: number + unit (ms|s|m|h|d|w|M|y)
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h|d|w|M|y)\$").matchEntire(s) ?: return null
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        return when (unit) {
            "ms" -> Duration(num.toLong(), unitIsDayLike = false, dayLikeCount = 0)
            "s" -> Duration((num * 1000.0).toLong(), unitIsDayLike = false, dayLikeCount = 0)
            "m" -> Duration((num * 60_000.0).toLong(), unitIsDayLike = false, dayLikeCount = 0)
            "h" -> Duration((num * 3_600_000.0).toLong(), unitIsDayLike = false, dayLikeCount = 0)
            "d" -> Duration((num * MS_PER_DAY.toDouble()).toLong(), unitIsDayLike = true, dayLikeCount = floor(num).toInt())
            "w" -> Duration((num * 7.0 * MS_PER_DAY.toDouble()).toLong(), unitIsDayLike = true, dayLikeCount = floor(num * 7.0).toInt())
            "M" -> Duration((num * 30.0 * MS_PER_DAY.toDouble()).toLong(), unitIsDayLike = true, dayLikeCount = floor(num * 30.0).toInt())
            "y" -> Duration((num * 365.0 * MS_PER_DAY.toDouble()).toLong(), unitIsDayLike = true, dayLikeCount = floor(num * 365.0).toInt())
            else -> null
        }
    }

    fun epochDay(epochMs: Long): Long = epochMs.floorDiv(MS_PER_DAY)

    /** 0..6 (Sun..Sat) */
    fun weekday(epochDay: Long): Int {
        // 1970-01-01 is Thursday. Map to 0..6 with Sunday=0.
        // Thursday index in Sunday-based is 4.
        val thursday = 4
        val v = (thursday + epochDay).mod(7)
        return v
    }

    // --- civil date helpers ---

    // Algorithm from Howard Hinnant's date algorithms (public domain)
    private fun civilToEpochDay(y: Int, m: Int, d: Int): Long {
        var year = y
        var month = m
        year -= if (month <= 2) 1 else 0
        val era = floorDiv(year, 400)
        val yoe = year - era * 400
        val mp = month + if (month > 2) -3 else 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097L + doe.toLong() - 719468L)
    }

    private fun floorDiv(a: Int, b: Int): Int {
        var q = a / b
        val r = a % b
        if (r != 0 && ((r > 0) != (b > 0))) q -= 1
        return q
    }
}
