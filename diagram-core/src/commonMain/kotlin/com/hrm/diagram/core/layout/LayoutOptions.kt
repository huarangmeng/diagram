package com.hrm.diagram.core.layout

import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.random.RandomSource

/**
 * Common knobs every layout algorithm honours. Algorithm-specific tuning lives in
 * dedicated subclasses defined in `:diagram-layout`.
 */
data class LayoutOptions(
    val seed: Long = RandomSource.DefaultSeed,
    val direction: Direction? = null,
    val padding: Insets = Insets.symmetric(16f),
    val nodeSpacing: Float = 24f,
    val rankSpacing: Float = 48f,
    /** Hard ceiling for the laid-out drawing; null = no clamp. */
    val maxCanvas: Size? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    val random: RandomSource get() = RandomSource(seed)
}

data class Insets(val top: Float, val right: Float, val bottom: Float, val left: Float) {
    init { require(top >= 0 && right >= 0 && bottom >= 0 && left >= 0) { "Insets must be non-negative" } }

    companion object {
        val Zero = Insets(0f, 0f, 0f, 0f)
        fun symmetric(v: Float) = Insets(v, v, v, v)
        fun symmetric(vertical: Float, horizontal: Float) = Insets(vertical, horizontal, vertical, horizontal)
    }
}
