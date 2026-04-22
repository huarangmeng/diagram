package com.hrm.diagram.core.draw

/**
 * Backend-neutral instruction stream emitted by the renderer pipeline after layout.
 * Compose, SVG and PNG/JPEG backends MUST consume the same `List<DrawCommand>`.
 *
 * Invariants (see docs/draw-command.md):
 *  - Pure function of (IR, Theme): identical inputs MUST produce identical lists.
 *  - All coordinates are in final canvas space; export backends only scale / re-encode.
 *  - No deferred-measure commands: every [DrawText] carries an explicit [FontSpec] and
 *    optional [DrawText.maxWidth]; layout owns the final box.
 */
sealed interface DrawCommand {
    /** Same-Z commands draw in list order. Higher Z draws above lower Z. */
    val z: Int

    data class FillRect(
        val rect: Rect,
        val color: Color,
        val corner: Float = 0f,
        override val z: Int = 0,
    ) : DrawCommand

    data class StrokeRect(
        val rect: Rect,
        val stroke: Stroke,
        val color: Color,
        val corner: Float = 0f,
        override val z: Int = 0,
    ) : DrawCommand

    data class FillPath(
        val path: PathCmd,
        val color: Color,
        override val z: Int = 0,
    ) : DrawCommand

    data class StrokePath(
        val path: PathCmd,
        val stroke: Stroke,
        val color: Color,
        override val z: Int = 0,
    ) : DrawCommand

    /** Origin is baseline-left. Width pre-measured by the layout layer. */
    data class DrawText(
        val text: String,
        val origin: Point,
        val font: FontSpec,
        val color: Color,
        val maxWidth: Float? = null,
        override val z: Int = 0,
    ) : DrawCommand

    data class DrawArrow(
        val from: Point,
        val to: Point,
        val style: ArrowStyle,
        override val z: Int = 0,
    ) : DrawCommand

    data class DrawIcon(
        val name: String,
        val rect: Rect,
        override val z: Int = 0,
    ) : DrawCommand

    data class Group(
        val children: List<DrawCommand>,
        val transform: Transform = Transform.Identity,
        override val z: Int = 0,
    ) : DrawCommand

    data class Clip(
        val rect: Rect,
        val children: List<DrawCommand>,
        override val z: Int = 0,
    ) : DrawCommand

    data class Hyperlink(
        val href: String,
        val rect: Rect,
        override val z: Int = 0,
    ) : DrawCommand
}
