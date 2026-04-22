package com.hrm.diagram.core.draw

enum class Cap { Butt, Round, Square }
enum class Join { Miter, Round, Bevel }

/** Stroke spec. `dash` uses [List] (not FloatArray) for stable equality / hashing. */
data class Stroke(
    val width: Float,
    val dash: List<Float>? = null,
    val cap: Cap = Cap.Butt,
    val join: Join = Join.Miter,
) {
    init { require(width >= 0f) { "stroke width must be non-negative" } }
    companion object { val Hairline = Stroke(1f) }
}

data class FontSpec(
    val family: String,
    val sizeSp: Float,
    val weight: Int = 400,
    val italic: Boolean = false,
) {
    init { require(sizeSp > 0f) { "font sizeSp must be > 0" } }
}

enum class ArrowHead { None, Triangle, OpenTriangle, Diamond, OpenDiamond, Circle, OpenCircle, Bar, Cross }

data class ArrowStyle(
    val head: ArrowHead = ArrowHead.Triangle,
    val tail: ArrowHead = ArrowHead.None,
    val color: Color = Color.Black,
    val stroke: Stroke = Stroke.Hairline,
)
