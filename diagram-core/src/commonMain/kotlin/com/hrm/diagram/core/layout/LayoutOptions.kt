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
    /**
     * Streaming knob (see `docs/streaming.md` §3.4). When `true`, layouts MUST attempt to reuse
     * coordinates from a previous run when given a `LaidOutDiagram` baseline. Default `true`
     * because LLM streaming is the primary use case.
     */
    val incremental: Boolean = true,
    /**
     * Opt-in escape hatch: if an incremental layout cannot extend the baseline without moving
     * existing nodes, allow it to fall back to a full re-layout. Default `false` so streaming UIs
     * never experience node "jumping". Non-streaming callers may set this to `true`.
     */
    val allowGlobalReflow: Boolean = false,
    /**
     * Maximum number of `DrawCommand`s the renderer is willing to materialise. Beyond this, the
     * pipeline emits a WARNING and stops appending. Default chosen to keep memory reasonable on
     * mobile devices.
     */
    val drawCommandBudget: Int = 50_000,
    val extras: Map<String, String> = emptyMap(),
) {
    init {
        require(drawCommandBudget > 0) { "drawCommandBudget must be positive, got $drawCommandBudget" }
    }
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
