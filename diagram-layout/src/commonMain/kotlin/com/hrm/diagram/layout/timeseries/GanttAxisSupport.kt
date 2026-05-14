package com.hrm.diagram.layout.timeseries

import kotlin.math.abs

object GanttAxisSupport {
    const val DAY_MS: Long = 86_400_000L
    const val WEEK_MS: Long = 7 * DAY_MS
    const val MONTH_MS: Long = 30 * DAY_MS

    fun parseTickInterval(raw: String?, spanMs: Long): Long {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return defaultTickInterval(spanMs)
        val digits = trimmed.takeWhile { it.isDigit() }
        val unit = trimmed.drop(digits.length).lowercase()
        val count = digits.toLongOrNull() ?: return defaultTickInterval(spanMs)
        val base = when (unit) {
            "millisecond" -> 1L
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> DAY_MS
            "week" -> WEEK_MS
            "month" -> MONTH_MS
            else -> return defaultTickInterval(spanMs)
        }
        return (count * base).coerceAtLeast(1L)
    }

    fun buildTicks(startMs: Long, endMs: Long, stepMs: Long): List<Long> {
        if (stepMs <= 0L) return emptyList()
        val out = ArrayList<Long>()
        var cur = startMs
        while (cur <= endMs) {
            out += cur
            val next = cur + stepMs
            if (next <= cur) break
            cur = next
        }
        if (out.isEmpty() || abs(out.last() - endMs) > stepMs / 3) out += endMs
        return out.distinct()
    }

    fun formatAxisTick(epochMs: Long, pattern: String): String {
        val parts = epochToParts(epochMs)
        val shortWeekdays = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val longWeekdays = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val shortMonths = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val longMonths = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        return buildString {
            var i = 0
            while (i < pattern.length) {
                if (pattern[i] != '%' || i == pattern.lastIndex) {
                    append(pattern[i])
                    i++
                    continue
                }
                when (pattern[i + 1]) {
                    '%' -> append('%')
                    'Y' -> append(parts.year.pad(4))
                    'y' -> append((parts.year % 100).pad(2))
                    'm' -> append(parts.month.pad(2))
                    'd' -> append(parts.day.pad(2))
                    'e' -> append(parts.day.toString())
                    'H' -> append(parts.hour.pad(2))
                    'M' -> append(parts.minute.pad(2))
                    'S' -> append(parts.second.pad(2))
                    'L' -> append(parts.millis.pad(3))
                    'b' -> append(shortMonths[parts.month - 1])
                    'B' -> append(longMonths[parts.month - 1])
                    'a' -> append(shortWeekdays[parts.weekday])
                    'A' -> append(longWeekdays[parts.weekday])
                    'w' -> append(parts.weekday)
                    's' -> append(epochMs / 1000L)
                    else -> {
                        append('%')
                        append(pattern[i + 1])
                    }
                }
                i += 2
            }
        }
    }

    fun formatMinorTick(epochMs: Long, pattern: String, stepMs: Long): String {
        val compactDailyDefault = stepMs in DAY_MS until WEEK_MS && pattern == "%Y-%m-%d"
        return if (compactDailyDefault) formatAxisTick(epochMs, "%d") else formatAxisTick(epochMs, pattern)
    }

    fun formatMajorTick(epochMs: Long, stepMs: Long): String =
        when {
            stepMs < DAY_MS -> formatAxisTick(epochMs, "%Y-%m-%d")
            stepMs < MONTH_MS -> formatAxisTick(epochMs, "%Y-%m")
            stepMs < 365 * DAY_MS -> formatAxisTick(epochMs, "%Y")
            else -> formatAxisTick(epochMs, "%Y")
        }

    fun majorTickKey(epochMs: Long, stepMs: Long): String =
        when {
            stepMs < DAY_MS -> formatAxisTick(epochMs, "%Y-%m-%d")
            stepMs < MONTH_MS -> formatAxisTick(epochMs, "%Y-%m")
            else -> formatAxisTick(epochMs, "%Y")
        }

    private fun defaultTickInterval(spanMs: Long): Long =
        when {
            spanMs <= 12 * 3_600_000L -> 3_600_000L
            spanMs <= 21 * DAY_MS -> DAY_MS
            spanMs <= 140 * DAY_MS -> WEEK_MS
            else -> MONTH_MS
        }

    private data class DateParts(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        val millis: Int,
        val weekday: Int,
    )

    private fun epochToParts(epochMs: Long): DateParts {
        val msPerDay = 86_400_000L
        val epochDay = floorDiv(epochMs, msPerDay)
        val dayMs = epochMs - epochDay * msPerDay
        val civil = epochDayToCivil(epochDay)
        val hour = (dayMs / 3_600_000L).toInt()
        val minute = ((dayMs % 3_600_000L) / 60_000L).toInt()
        val second = ((dayMs % 60_000L) / 1000L).toInt()
        val millis = (dayMs % 1000L).toInt()
        val weekday = (4 + epochDay).mod(7)
        return DateParts(civil.first, civil.second, civil.third, hour, minute, second, millis, weekday)
    }

    private fun epochDayToCivil(epochDay: Long): Triple<Int, Int, Int> {
        var z = epochDay + 719468L
        val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
        val doe = (z - era * 146097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var y = yoe + era.toInt() * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = mp + if (mp < 10) 3 else -9
        y += if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        val r = a % b
        if (r != 0L && ((r > 0) != (b > 0))) q -= 1
        return q
    }

    private fun Int.pad(width: Int): String = toString().padStart(width, '0')
    private fun Long.pad(width: Int): String = toString().padStart(width, '0')
}
