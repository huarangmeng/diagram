package com.hrm.diagram.parser.plantuml

internal object PlantUmlTemporalSupport {
    const val MS_PER_DAY: Long = 86_400_000L
    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?$""")
    private val DURATION = Regex("""^(\d+(?:\.\d+)?)\s*(millisecond|milliseconds|ms|second|seconds|s|minute|minutes|min|hour|hours|h|day|days|d|week|weeks|w|month|months|year|years)$""", RegexOption.IGNORE_CASE)

    fun parseDate(raw: String): Long? {
        val m = DATE.matchEntire(raw.trim()) ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val d = m.groupValues[3].toInt()
        val hh = m.groupValues[4].toIntOrNull() ?: 0
        val mm = m.groupValues[5].toIntOrNull() ?: 0
        val ss = m.groupValues[6].toIntOrNull() ?: 0
        return epochDay(y, mo, d) * MS_PER_DAY + hh * 3_600_000L + mm * 60_000L + ss * 1_000L
    }

    fun parseDuration(raw: String): Long? {
        val m = DURATION.matchEntire(raw.trim()) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val base = when (unit) {
            "millisecond", "milliseconds", "ms" -> 1L
            "second", "seconds", "s" -> 1_000L
            "minute", "minutes", "min" -> 60_000L
            "hour", "hours", "h" -> 3_600_000L
            "day", "days", "d" -> MS_PER_DAY
            "week", "weeks", "w" -> 7 * MS_PER_DAY
            "month", "months" -> 30 * MS_PER_DAY
            "year", "years" -> 365 * MS_PER_DAY
            else -> return null
        }
        return (value * base).toLong().coerceAtLeast(1L)
    }

    fun parseOffset(raw: String): Long? {
        val s = raw.trim().removePrefix("@")
        if (s.isEmpty()) return null
        parseDate(s)?.let { return it }
        val numeric = s.toLongOrNull()
        if (numeric != null) return numeric
        return parseDuration(s)
    }

    fun slug(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_.:-]+"), "_").trim('_').ifBlank { "item" }

    private fun epochDay(year: Int, month: Int, day: Int): Long {
        var y = year
        val m = month
        y -= if (m <= 2) 1 else 0
        val era = if (y >= 0) y / 400 else (y - 399) / 400
        val yoe = y - era * 400
        val mp = m + if (m > 2) -3 else 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097 + doe - 719468).toLong()
    }
}
