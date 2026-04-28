package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color

internal object MermaidRenderThemeUtils {
    fun decodeRawThemeTokens(encoded: String?): Map<String, String> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in encoded.lines()) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1)
            if (key.startsWith("raw.")) out[key.removePrefix("raw.")] = value
        }
        return out
    }

    fun parseThemeColor(text: String?): Color? {
        val s = text?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        if (s.startsWith("#")) {
            val h = s.removePrefix("#")
            return when (h.length) {
                3 -> Color(0xFF000000.toInt() or expand3(h))
                6 -> Color((0xFF000000.toInt() or h.toIntOrNull(16)!!))
                8 -> Color(h.toLongOrNull(16)?.toInt() ?: return null)
                else -> null
            }
        }
        val named = when (s) {
            "white" -> 0xFFFFFFFF.toInt()
            "black" -> 0xFF000000.toInt()
            "red" -> 0xFFFF0000.toInt()
            "green" -> 0xFF008000.toInt()
            "blue" -> 0xFF0000FF.toInt()
            "yellow" -> 0xFFFFFF00.toInt()
            "gray", "grey" -> 0xFF808080.toInt()
            else -> return null
        }
        return Color(named)
    }

    private fun expand3(h: String): Int {
        val r = "${h[0]}${h[0]}".toInt(16)
        val g = "${h[1]}${h[1]}".toInt(16)
        val b = "${h[2]}${h[2]}".toInt(16)
        return (r shl 16) or (g shl 8) or b
    }
}

