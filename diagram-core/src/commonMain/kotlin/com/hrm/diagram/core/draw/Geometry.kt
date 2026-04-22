package com.hrm.diagram.core.draw

import kotlin.jvm.JvmInline

/** Top-left origin, Y-axis points down, units = px @ 1x DPI. */
data class Point(val x: Float, val y: Float) {
    companion object { val Zero = Point(0f, 0f) }
}

data class Size(val width: Float, val height: Float) {
    init {
        require(width >= 0f && height >= 0f) { "Size must be non-negative: ($width, $height)" }
    }
    companion object { val Zero = Size(0f, 0f) }
}

data class Rect(val origin: Point, val size: Size) {
    val left: Float get() = origin.x
    val top: Float get() = origin.y
    val right: Float get() = origin.x + size.width
    val bottom: Float get() = origin.y + size.height

    fun contains(p: Point): Boolean =
        p.x in left..right && p.y in top..bottom

    companion object {
        fun ltrb(left: Float, top: Float, right: Float, bottom: Float): Rect =
            Rect(Point(left, top), Size(right - left, bottom - top))
    }
}

sealed interface PathOp {
    data class MoveTo(val p: Point) : PathOp
    data class LineTo(val p: Point) : PathOp
    data class QuadTo(val ctrl: Point, val end: Point) : PathOp
    data class CubicTo(val c1: Point, val c2: Point, val end: Point) : PathOp
    data object Close : PathOp
}

data class PathCmd(val ops: List<PathOp>)

@JvmInline
value class Color(val argb: Int) {
    val a: Int get() = (argb ushr 24) and 0xFF
    val r: Int get() = (argb ushr 16) and 0xFF
    val g: Int get() = (argb ushr 8) and 0xFF
    val b: Int get() = argb and 0xFF

    companion object {
        val Black = Color(0xFF000000.toInt())
        val White = Color(0xFFFFFFFF.toInt())
        val Transparent = Color(0x00000000)
        fun argb(a: Int, r: Int, g: Int, b: Int): Color =
            Color(((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF))
    }
}

data class Transform(
    val translate: Point = Point.Zero,
    val scale: Float = 1f,
    val rotateDeg: Float = 0f,
) {
    val isIdentity: Boolean
        get() = translate.x == 0f && translate.y == 0f && scale == 1f && rotateDeg == 0f
    companion object { val Identity = Transform() }
}
