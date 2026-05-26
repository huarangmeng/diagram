package com.hrm.diagram.core.color

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.ir.ArgbColor

/**
 * Shared color token parser for Mermaid / PlantUML / DOT style values.
 *
 * Example:
 * ```kotlin
 * val fill = ColorTokenParser.parseColor("lightskyblue")
 * ```
 */
@DiagramApi
object ColorTokenParser {
    fun parseArgb(raw: String?): Int? {
        val value = raw?.trim()?.removeSurrounding("\"") ?: return null
        if (value.isEmpty()) return null
        val hex = value.removePrefix("#")
        return when {
            hex.length == 3 && hex.all(::isHexDigit) -> 0xFF000000.toInt() or expandShortHex(hex)
            hex.length == 6 && hex.all(::isHexDigit) -> (0xFF000000L or hex.toLong(16)).toInt()
            hex.length == 8 && hex.all(::isHexDigit) -> hex.toLong(16).toInt()
            else -> namedArgb(value)
        }
    }

    fun parseColor(raw: String?): Color? = parseArgb(raw)?.let(::Color)

    fun parseArgbColor(raw: String?): ArgbColor? = parseArgb(raw)?.let(::ArgbColor)

    private fun namedArgb(value: String): Int? =
        when (value.lowercase()) {
            "black" -> 0xFF000000.toInt()
            "white" -> 0xFFFFFFFF.toInt()
            "red" -> 0xFFFF0000.toInt()
            "green" -> 0xFF008000.toInt()
            "blue" -> 0xFF0000FF.toInt()
            "yellow" -> 0xFFFFFF00.toInt()
            "orange" -> 0xFFFFA500.toInt()
            "purple" -> 0xFF8E24AA.toInt()
            "gray", "grey" -> 0xFF808080.toInt()
            "lightblue" -> 0xFFADD8E6.toInt()
            "lightskyblue" -> 0xFF87CEFA.toInt()
            "skyblue" -> 0xFF87CEEB.toInt()
            "lightgreen" -> 0xFF90EE90.toInt()
            "palegreen" -> 0xFF98FB98.toInt()
            "lightyellow" -> 0xFFFFFFE0.toInt()
            "lightgray", "lightgrey" -> 0xFFD3D3D3.toInt()
            "saddlebrown" -> 0xFF8B4513.toInt()
            "silver" -> 0xFFC0C0C0.toInt()
            "peru" -> 0xFFCD853F.toInt()
            "navy" -> 0xFF000080.toInt()
            "ivory" -> 0xFFFFFFF0.toInt()
            "pink" -> 0xFFFFC0CB.toInt()
            else -> null
        }

    private fun isHexDigit(ch: Char): Boolean = ch.isDigit() || ch.lowercaseChar() in 'a'..'f'

    private fun expandShortHex(hex: String): Int =
        ("${hex[0]}${hex[0]}".toInt(16) shl 16) or
            ("${hex[1]}${hex[1]}".toInt(16) shl 8) or
            "${hex[2]}${hex[2]}".toInt(16)
}
