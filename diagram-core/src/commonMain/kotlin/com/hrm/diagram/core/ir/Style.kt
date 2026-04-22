package com.hrm.diagram.core.ir

/** Coarse shape vocabulary covering the union of Mermaid / PlantUML / DOT node shapes. */
sealed interface NodeShape {
    data object Box : NodeShape
    data object RoundedBox : NodeShape
    data object Stadium : NodeShape
    data object Circle : NodeShape
    data object Ellipse : NodeShape
    data object Diamond : NodeShape
    data object Hexagon : NodeShape
    data object Parallelogram : NodeShape
    data object Trapezoid : NodeShape
    data object Cylinder : NodeShape
    data object Subroutine : NodeShape
    data object Note : NodeShape
    data object Cloud : NodeShape
    data object Actor : NodeShape
    data object UseCase : NodeShape
    data object Component : NodeShape
    data object Package : NodeShape
    data object State : NodeShape
    data object ChoicePoint : NodeShape
    data object ForkBar : NodeShape
    data object StartCircle : NodeShape
    data object EndCircle : NodeShape
    data object JsonNode : NodeShape
    /** Backend-defined shape; renderer may fall back to [Box] with a [docs/diagnostics.md] warning. */
    data class Custom(val name: String) : NodeShape
}

/** Edge line style. */
enum class EdgeKind { Solid, Dashed, Dotted, Thick, Invisible }

/** Which ends of an edge get an arrowhead. */
enum class ArrowEnds { None, ToOnly, FromOnly, Both }

data class NodeStyle(
    val fill: ArgbColor? = null,
    val stroke: ArgbColor? = null,
    val strokeWidth: Float? = null,
    val textColor: ArgbColor? = null,
) {
    companion object { val Default = NodeStyle() }
}

data class EdgeStyle(
    val color: ArgbColor? = null,
    val width: Float? = null,
    val dash: List<Float>? = null,
    val labelBg: ArgbColor? = null,
) {
    companion object { val Default = EdgeStyle() }
}

data class ClusterStyle(
    val fill: ArgbColor? = null,
    val stroke: ArgbColor? = null,
    val strokeWidth: Float? = null,
) {
    companion object { val Default = ClusterStyle() }
}

/** Packed ARGB so that IR equality is structural and stable across platforms. */
@kotlin.jvm.JvmInline
value class ArgbColor(val argb: Int)
