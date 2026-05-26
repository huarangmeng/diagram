package com.hrm.diagram.core.color

import com.hrm.diagram.core.DiagramApi
/**
 * Shared Graphviz DOT color parser used by both parser and renderer paths.
 *
 * Minimal usage:
 * ```kotlin
 * val color = DotColorParser.parseColor("#336699")
 * ```
 */
@DiagramApi
object DotColorParser {
    /**
     * Parses a DOT color token into packed ARGB.
     */
    fun parseArgb(raw: String?): Int? = ColorTokenParser.parseArgb(raw)

    /**
     * Parses a DOT color token into render-time [Color].
     */
    fun parseColor(raw: String?) = ColorTokenParser.parseColor(raw)

    /**
     * Parses a DOT color token into IR-time [ArgbColor].
     */
    fun parseArgbColor(raw: String?) = ColorTokenParser.parseArgbColor(raw)
}
