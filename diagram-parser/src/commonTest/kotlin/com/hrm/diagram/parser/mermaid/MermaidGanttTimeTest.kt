package com.hrm.diagram.parser.mermaid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MermaidGanttTimeTest {

    @Test
    fun parses_month_names_and_meridiem_tokens() {
        val baseline = MermaidGanttTime.parseDateTime("2014-01-02 05:06:07", "YYYY-MM-DD HH:mm:ss")
        val parsed = MermaidGanttTime.parseDateTime("January 2, 2014 5:06:07 am", "MMMM D, YYYY h:mm:ss a")
        assertNotNull(baseline)
        assertNotNull(parsed)
        assertEquals(baseline.epochMs, parsed.epochMs)
    }

    @Test
    fun parses_short_month_and_two_digit_year_tokens() {
        val baseline = MermaidGanttTime.parseDateTime("2014-02-03", "YYYY-MM-DD")
        val parsed = MermaidGanttTime.parseDateTime("Feb 03 14", "MMM DD YY")
        assertNotNull(baseline)
        assertNotNull(parsed)
        assertEquals(baseline.epochMs, parsed.epochMs)
    }

    @Test
    fun parses_quarter_and_day_of_year_tokens() {
        val quarter = MermaidGanttTime.parseDateTime("2014/2", "YYYY/Q")
        val quarterBaseline = MermaidGanttTime.parseDateTime("2014-04-01", "YYYY-MM-DD")
        val dayOfYear = MermaidGanttTime.parseDateTime("2014-032", "YYYY-DDD")
        val dayOfYearBaseline = MermaidGanttTime.parseDateTime("2014-02-01", "YYYY-MM-DD")
        assertNotNull(quarter)
        assertNotNull(quarterBaseline)
        assertEquals(quarterBaseline.epochMs, quarter.epochMs)
        assertNotNull(dayOfYear)
        assertNotNull(dayOfYearBaseline)
        assertEquals(dayOfYearBaseline.epochMs, dayOfYear.epochMs)
    }

    @Test
    fun parses_ordinal_and_timezone_tokens() {
        val ordinal = MermaidGanttTime.parseDateTime("2014-1st", "YYYY-Do")
        val ordinalBaseline = MermaidGanttTime.parseDateTime("2014-01-01", "YYYY-MM-DD")
        val zoned = MermaidGanttTime.parseDateTime("2014-01-02 05:00 +02:00", "YYYY-MM-DD HH:mm Z")
        val zonedBaseline = MermaidGanttTime.parseDateTime("2014-01-02 03:00", "YYYY-MM-DD HH:mm")
        assertNotNull(ordinal)
        assertNotNull(ordinalBaseline)
        assertEquals(ordinalBaseline.epochMs, ordinal.epochMs)
        assertNotNull(zoned)
        assertNotNull(zonedBaseline)
        assertEquals(zonedBaseline.epochMs, zoned.epochMs)
    }

    @Test
    fun adds_calendar_months_with_end_of_month_clamp() {
        val start = MermaidGanttTime.parseDateTime("2024-01-31", "YYYY-MM-DD")
        val expected = MermaidGanttTime.parseDateTime("2024-02-29", "YYYY-MM-DD")
        assertNotNull(start)
        assertNotNull(expected)
        assertEquals(expected.epochMs, MermaidGanttTime.addCalendarMonths(start.epochMs, 1))
    }

    @Test
    fun adds_calendar_years_with_leap_day_clamp() {
        val start = MermaidGanttTime.parseDateTime("2024-02-29", "YYYY-MM-DD")
        val expected = MermaidGanttTime.parseDateTime("2025-02-28", "YYYY-MM-DD")
        assertNotNull(start)
        assertNotNull(expected)
        assertEquals(expected.epochMs, MermaidGanttTime.addCalendarYears(start.epochMs, 1))
    }
}
