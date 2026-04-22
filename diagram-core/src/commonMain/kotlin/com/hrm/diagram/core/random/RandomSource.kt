package com.hrm.diagram.core.random

import kotlin.random.Random

/**
 * Deterministic random source for layout algorithms.
 *
 * **Layout MUST NOT touch [kotlin.random.Random.Default] or wall-clock time** (see
 * docs/rules.md §C). Always thread a [RandomSource] derived from the user-provided
 * `LayoutOptions.seed`; identical `(model, seed)` MUST yield identical geometry.
 */
class RandomSource(val seed: Long) {
    private val rng: Random = Random(seed)

    fun nextFloat(): Float = rng.nextFloat()
    fun nextDouble(): Double = rng.nextDouble()
    fun nextInt(): Int = rng.nextInt()
    fun nextInt(from: Int, until: Int): Int = rng.nextInt(from, until)
    fun nextLong(): Long = rng.nextLong()
    fun nextBoolean(): Boolean = rng.nextBoolean()

    /** Mix in a child salt to derive an independent sub-stream (e.g. per-node jitter). */
    fun derive(salt: Long): RandomSource = RandomSource(scramble(seed xor salt))

    private fun scramble(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x4b6d499041670d8dL
        z = (z xor (z ushr 27)) * -0x3b314601e57a13adL
        return z xor (z ushr 31)
    }

    companion object {
        const val DefaultSeed: Long = 0xC0FFEEL
        val Default: RandomSource get() = RandomSource(DefaultSeed)
    }
}
