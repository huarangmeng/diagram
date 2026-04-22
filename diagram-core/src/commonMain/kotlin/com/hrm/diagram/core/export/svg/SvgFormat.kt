package com.hrm.diagram.core.export.svg

import kotlin.math.roundToLong

/**
 * Lossy-but-stable float formatter for SVG attributes.
 * Trims to at most 3 decimals and drops trailing zeros so snapshots stay deterministic
 * across JVM/JS/Wasm/Native (each platform's `Float.toString()` differs subtly).
 */
internal fun fmt(v: Float): String {
    if (v == 0f) return "0"
    val negative = v < 0f
    val abs = if (negative) -v else v
    val scaled = (abs.toDouble() * 1000.0).roundToLong()
    if (scaled == 0L) return "0"
    val intPart = scaled / 1000
    val fracPart = (scaled % 1000).toInt()
    val sign = if (negative) "-" else ""
    if (fracPart == 0) return "$sign$intPart"
    val frac = fracPart.toString().padStart(3, '0').trimEnd('0')
    return "$sign$intPart.$frac"
}

internal fun escapeXml(s: String): String {
    if (s.isEmpty()) return s
    val sb = StringBuilder(s.length)
    for (c in s) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

internal fun escapeAttr(s: String): String = escapeXml(s)
