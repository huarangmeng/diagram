package com.hrm.diagram.parser.mermaid

import kotlin.math.floor

/**
 * CommonMain date/time + duration helpers for Mermaid gantt.
 *
 * Input contract:
 * - Parse Mermaid `dateFormat` tokens rather than delegating to Day.js.
 * - Calendar math is Gregorian proleptic.
 * - When no timezone token is present, values are interpreted as UTC.
 */
internal object MermaidGanttTime {
    const val MS_PER_DAY: Long = 86_400_000L

    data class DateTime(val epochMs: Long)

    enum class DurationUnit {
        Millisecond,
        Second,
        Minute,
        Hour,
        Day,
        Week,
        Month,
        Year,
    }

    data class Duration(val value: Double, val unit: DurationUnit) {
        val millis: Long?
            get() = when (unit) {
                DurationUnit.Millisecond -> value.toLong()
                DurationUnit.Second -> (value * 1000.0).toLong()
                DurationUnit.Minute -> (value * 60_000.0).toLong()
                DurationUnit.Hour -> (value * 3_600_000.0).toLong()
                else -> null
            }
    }

    fun parseDateTime(text: String, format: String): DateTime? {
        val s = text.trim()
        if (s.isEmpty()) return null
        if (format == "X") return s.toDoubleOrNull()?.let { DateTime((it * 1000.0).toLong()) }
        if (format == "x") return s.toDoubleOrNull()?.let { DateTime(it.toLong()) }

        val tokens = tokenizeFormat(format) ?: return null
        val state = ParseState()
        var index = 0

        for (token in tokens) {
            when (token) {
                is FormatPart.Literal -> {
                    index = matchLiteral(s, index, token.text) ?: return null
                }
                is FormatPart.Token -> {
                    index = readToken(s, index, token.value, state) ?: return null
                }
            }
        }
        while (index < s.length && s[index].isWhitespace()) index++
        if (index != s.length) return null

        val resolved = state.resolve() ?: return null
        return DateTime(resolved)
    }

    fun parseDuration(text: String): Duration? {
        val s = text.trim().replace(" ", "")
        if (s.isEmpty()) return null
        // Match: number + unit (ms|s|m|h|d|w|M|y)
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h|d|w|M|y)\$").matchEntire(s) ?: return null
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        return when (unit) {
            "ms" -> Duration(num, DurationUnit.Millisecond)
            "s" -> Duration(num, DurationUnit.Second)
            "m" -> Duration(num, DurationUnit.Minute)
            "h" -> Duration(num, DurationUnit.Hour)
            "d" -> Duration(num, DurationUnit.Day)
            "w" -> Duration(num, DurationUnit.Week)
            "M" -> Duration(num, DurationUnit.Month)
            "y" -> Duration(num, DurationUnit.Year)
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

    fun addCalendarMonths(epochMs: Long, months: Int): Long {
        if (months == 0) return epochMs
        val epochDay = epochDay(epochMs)
        val civil = epochDayToCivil(epochDay)
        val millisInDay = epochMs - epochDay * MS_PER_DAY
        val monthIndex = civil.first * 12 + (civil.second - 1) + months
        val targetYear = floorDiv(monthIndex, 12)
        val targetMonth = monthIndex - targetYear * 12 + 1
        val targetDay = minOf(civil.third, daysInMonth(targetYear, targetMonth))
        val targetEpochDay = civilToEpochDay(targetYear, targetMonth, targetDay)
        return targetEpochDay * MS_PER_DAY + millisInDay
    }

    fun addCalendarYears(epochMs: Long, years: Int): Long = addCalendarMonths(epochMs, years * 12)

    // --- civil date helpers ---

    private sealed interface FormatPart {
        data class Literal(val text: String) : FormatPart
        data class Token(val value: String) : FormatPart
    }

    private class ParseState {
        var year: Int? = null
        var month: Int? = null
        var day: Int? = null
        var quarter: Int? = null
        var dayOfYear: Int? = null
        var hour24: Int? = null
        var hour12: Int? = null
        var meridiem: String? = null
        var minute: Int = 0
        var second: Int = 0
        var millis: Int = 0
        var offsetMinutes: Int = 0

        fun resolve(): Long? {
            val y = year ?: 1970
            val resolvedMonth = when {
                month != null -> month
                quarter != null -> (quarter!! - 1) * 3 + 1
                else -> 1
            } ?: return null
            var resolvedDay = day ?: 1
            var resolvedYear = y
            var finalMonth = resolvedMonth
            if (dayOfYear != null) {
                val epochDay = civilToEpochDay(y, 1, 1) + (dayOfYear!! - 1)
                val civil = epochDayToCivil(epochDay)
                resolvedYear = civil.first
                finalMonth = civil.second
                resolvedDay = civil.third
            }
            if (finalMonth !in 1..12) return null
            if (resolvedDay !in 1..31) return null
            val hour = when {
                hour24 != null -> hour24!!
                hour12 != null -> {
                    val base = hour12!! % 12
                    when (meridiem?.lowercase()) {
                        "pm" -> base + 12
                        else -> base
                    }
                }
                else -> 0
            }
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..59 || millis !in 0..999) return null
            val epochDay = civilToEpochDay(resolvedYear, finalMonth, resolvedDay)
            val localMs = epochDay * MS_PER_DAY + hour * 3_600_000L + minute * 60_000L + second * 1000L + millis
            return localMs - offsetMinutes * 60_000L
        }
    }

    private val knownTokens = listOf(
        "YYYY", "MMMM", "MMM", "DDDD", "DDD", "Do", "SSS", "ZZ", "YY",
        "MM", "DD", "HH", "hh", "mm", "ss", "SS", "Q", "M", "D", "H", "h", "m", "s", "S", "A", "a", "Z",
    )

    private val monthNamesLong = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    private val monthNamesShort = listOf(
        "jan", "feb", "mar", "apr", "may", "jun",
        "jul", "aug", "sep", "oct", "nov", "dec",
    )

    private fun tokenizeFormat(format: String): List<FormatPart>? {
        val out = ArrayList<FormatPart>()
        var i = 0
        while (i < format.length) {
            val token = knownTokens.firstOrNull { format.startsWith(it, i) }
            if (token != null) {
                out += FormatPart.Token(token)
                i += token.length
            } else {
                out += FormatPart.Literal(format[i].toString())
                i++
            }
        }
        return out
    }

    private fun matchLiteral(input: String, start: Int, literal: String): Int? {
        var i = start
        if (literal.all { it.isWhitespace() }) {
            while (i < input.length && input[i].isWhitespace()) i++
            return i
        }
        while (i < input.length && input[i].isWhitespace() && literal.first().isWhitespace()) i++
        return if (input.startsWith(literal, i)) i + literal.length else null
    }

    private fun readToken(input: String, start: Int, token: String, state: ParseState): Int? {
        var i = start
        while (i < input.length && input[i].isWhitespace()) i++
        when (token) {
            "YYYY" -> {
                val v = readExactDigits(input, i, 4) ?: return null
                state.year = v.first
                return v.second
            }
            "YY" -> {
                val v = readExactDigits(input, i, 2) ?: return null
                state.year = if (v.first >= 69) 1900 + v.first else 2000 + v.first
                return v.second
            }
            "Q" -> {
                val v = readVariableDigits(input, i, 1, 1) ?: return null
                if (v.first !in 1..4) return null
                state.quarter = v.first
                return v.second
            }
            "MMMM" -> {
                val v = readMonthName(input, i, monthNamesLong) ?: return null
                state.month = v.first
                return v.second
            }
            "MMM" -> {
                val v = readMonthName(input, i, monthNamesShort) ?: return null
                state.month = v.first
                return v.second
            }
            "MM" -> {
                val v = readExactDigits(input, i, 2) ?: return null
                state.month = v.first
                return v.second
            }
            "M" -> {
                val v = readVariableDigits(input, i, 1, 2) ?: return null
                state.month = v.first
                return v.second
            }
            "DDDD" -> {
                val v = readExactDigits(input, i, 4) ?: return null
                state.dayOfYear = v.first
                return v.second
            }
            "DDD" -> {
                val v = readVariableDigits(input, i, 1, 3) ?: return null
                state.dayOfYear = v.first
                return v.second
            }
            "Do" -> {
                val v = readOrdinalDay(input, i) ?: return null
                state.day = v.first
                return v.second
            }
            "DD" -> {
                val v = readExactDigits(input, i, 2) ?: return null
                state.day = v.first
                return v.second
            }
            "D" -> {
                val v = readVariableDigits(input, i, 1, 2) ?: return null
                state.day = v.first
                return v.second
            }
            "HH" -> {
                val v = readExactDigits(input, i, 2) ?: return null
                state.hour24 = v.first
                return v.second
            }
            "H" -> {
                val v = readVariableDigits(input, i, 1, 2) ?: return null
                state.hour24 = v.first
                return v.second
            }
            "hh" -> {
                val v = readExactDigits(input, i, 2) ?: return null
                state.hour12 = v.first
                return v.second
            }
            "h" -> {
                val v = readVariableDigits(input, i, 1, 2) ?: return null
                state.hour12 = v.first
                return v.second
            }
            "mm" -> {
                val v = readExactDigits(input, i, 2) ?: return null
                state.minute = v.first
                return v.second
            }
            "m" -> {
                val v = readVariableDigits(input, i, 1, 2) ?: return null
                state.minute = v.first
                return v.second
            }
            "ss" -> {
                val v = readExactDigits(input, i, 2) ?: return null
                state.second = v.first
                return v.second
            }
            "s" -> {
                val v = readVariableDigits(input, i, 1, 2) ?: return null
                state.second = v.first
                return v.second
            }
            "SSS" -> {
                val v = readExactDigits(input, i, 3) ?: return null
                state.millis = v.first
                return v.second
            }
            "SS" -> {
                val v = readExactDigits(input, i, 2) ?: return null
                state.millis = v.first * 10
                return v.second
            }
            "S" -> {
                val v = readExactDigits(input, i, 1) ?: return null
                state.millis = v.first * 100
                return v.second
            }
            "A", "a" -> {
                val v = readMeridiem(input, i) ?: return null
                state.meridiem = v.first
                return v.second
            }
            "Z", "ZZ" -> {
                val v = readOffset(input, i, allowColon = token == "Z") ?: return null
                state.offsetMinutes = v.first
                return v.second
            }
        }
        return null
    }

    private fun readExactDigits(input: String, start: Int, count: Int): Pair<Int, Int>? {
        if (start + count > input.length) return null
        val sub = input.substring(start, start + count)
        if (!sub.all { it.isDigit() }) return null
        return sub.toInt() to (start + count)
    }

    private fun readVariableDigits(input: String, start: Int, min: Int, max: Int): Pair<Int, Int>? {
        var end = start
        while (end < input.length && end - start < max && input[end].isDigit()) end++
        if (end - start < min) return null
        return input.substring(start, end).toInt() to end
    }

    private fun readMonthName(input: String, start: Int, names: List<String>): Pair<Int, Int>? {
        val remainder = input.substring(start)
        val match = names.withIndex().firstOrNull { (_, name) -> remainder.startsWith(name, ignoreCase = true) } ?: return null
        return (match.index + 1) to (start + match.value.length)
    }

    private fun readOrdinalDay(input: String, start: Int): Pair<Int, Int>? {
        val digits = readVariableDigits(input, start, 1, 2) ?: return null
        val suffixStart = digits.second
        if (suffixStart + 2 > input.length) return null
        val suffix = input.substring(suffixStart, suffixStart + 2).lowercase()
        if (suffix !in setOf("st", "nd", "rd", "th")) return null
        return digits.first to (suffixStart + 2)
    }

    private fun readMeridiem(input: String, start: Int): Pair<String, Int>? {
        if (start + 2 > input.length) return null
        val sub = input.substring(start, start + 2)
        val lower = sub.lowercase()
        if (lower != "am" && lower != "pm") return null
        return lower to (start + 2)
    }

    private fun readOffset(input: String, start: Int, allowColon: Boolean): Pair<Int, Int>? {
        if (start >= input.length) return null
        if (input[start] == 'Z') return 0 to (start + 1)
        val sign = when (input[start]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val hh = readExactDigits(input, start + 1, 2) ?: return null
        val mm = if (allowColon && hh.second < input.length && input[hh.second] == ':') {
            readExactDigits(input, hh.second + 1, 2)?.also { if (it.first !in 0..59) return null }?.let { it.first to it.second }
                ?: return null
        } else {
            val raw = readExactDigits(input, hh.second, 2) ?: return null
            if (raw.first !in 0..59) return null
            raw
        }
        if (hh.first !in 0..23) return null
        return sign * (hh.first * 60 + mm.first) to mm.second
    }

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

    private fun epochDayToCivil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468L
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

    private fun daysInMonth(year: Int, month: Int): Int {
        return when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 30
        }
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    private fun floorDiv(a: Int, b: Int): Int {
        var q = a / b
        val r = a % b
        if (r != 0 && ((r > 0) != (b > 0))) q -= 1
        return q
    }
}
