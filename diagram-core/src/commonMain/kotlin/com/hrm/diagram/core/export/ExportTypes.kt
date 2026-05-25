package com.hrm.diagram.core.export

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.ir.Diagnostic

/**
 * Stable, renderer-neutral export payload produced by the render bridge.
 *
 * Minimal usage:
 * ```kotlin
 * val rendered = RenderedDiagram(
 *     bounds = bounds,
 *     drawCommands = commands,
 *     background = null,
 * )
 * ```
 */
@DiagramApi
data class RenderedDiagram(
    val bounds: Rect,
    val drawCommands: List<DrawCommand>,
    val background: Color? = null,
)

/**
 * Output scaling policy for export backends.
 *
 * Export backends must preserve aspect ratio; `Width` and `Height` are therefore interpreted as
 * "scale until the requested axis matches".
 */
@DiagramApi
sealed interface ExportScale {
    @DiagramApi
    data object Intrinsic : ExportScale

    @DiagramApi
    data class Factor(val value: Float) : ExportScale {
        init {
            require(value > 0f) { "ExportScale.Factor must be > 0: $value" }
        }
    }

    @DiagramApi
    data class Width(val px: Int) : ExportScale {
        init {
            require(px > 0) { "ExportScale.Width must be > 0: $px" }
        }
    }

    @DiagramApi
    data class Height(val px: Int) : ExportScale {
        init {
            require(px > 0) { "ExportScale.Height must be > 0: $px" }
        }
    }
}

/** Background policy requested by the export caller. */
@DiagramApi
sealed interface ExportBackground {
    @DiagramApi
    data object Auto : ExportBackground

    @DiagramApi
    data object Transparent : ExportBackground

    @DiagramApi
    data class Solid(val color: Color) : ExportBackground
}

/**
 * Detailed export result. Convenience `toSvg()` / `toPng()` wrappers may expose only `value`,
 * but lower-level export APIs keep metadata and warnings.
 */
@DiagramApi
data class ExportArtifact<T>(
    val value: T,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * Options for SVG export.
 *
 * Minimal usage:
 * ```kotlin
 * val options = SvgExportOptions(
 *     scale = ExportScale.Width(1600),
 *     background = ExportBackground.Transparent,
 *     pretty = true,
 * )
 * ```
 */
@DiagramApi
data class SvgExportOptions(
    val scale: ExportScale = ExportScale.Intrinsic,
    val background: ExportBackground = ExportBackground.Auto,
    val pretty: Boolean = false,
    val embedFonts: Boolean = false,
    val includeXmlDeclaration: Boolean = true,
)

/** Shared options for raster export backends. Declared now so Phase 7 can build on one model. */
@DiagramApi
data class RasterExportOptions(
    val scale: ExportScale = ExportScale.Intrinsic,
    val background: ExportBackground = ExportBackground.Auto,
)

/** JPEG-specific options. Declared now even before Phase 7 raster actuals land. */
@DiagramApi
data class JpegExportOptions(
    val scale: ExportScale = ExportScale.Intrinsic,
    val background: ExportBackground = ExportBackground.Auto,
    val quality: Int = 90,
) {
    init {
        require(quality in 1..100) { "JpegExportOptions.quality must be in 1..100: $quality" }
    }
}
