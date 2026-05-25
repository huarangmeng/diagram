package com.hrm.diagram.core.export.svg

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.export.ExportArtifact
import com.hrm.diagram.core.export.ExportBackground
import com.hrm.diagram.core.export.ExportScale
import com.hrm.diagram.core.export.RenderedDiagram
import com.hrm.diagram.core.export.SvgExportOptions
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import kotlin.math.roundToInt

/**
 * Export this rendered frame as SVG text.
 *
 * Minimal usage:
 * ```kotlin
 * val artifact = rendered.exportSvg()
 * val svg = artifact.value
 * ```
 */
@DiagramApi
fun RenderedDiagram.exportSvg(
    options: SvgExportOptions = SvgExportOptions(),
): ExportArtifact<String> {
    val diagnostics = buildList {
        if (options.embedFonts) {
            add(
                Diagnostic(
                    severity = Severity.WARNING,
                    code = "EXPORT-W001",
                    message = "SVG embedFonts is not implemented yet; exporter keeps font family names only.",
                ),
            )
        }
    }
    val outputSize = scaleSize(bounds.size, options.scale)
    val background = resolveSvgBackground(this.background, options.background)
    val svg = SvgWriter(
        viewBox = bounds,
        outputSize = outputSize,
        background = background,
        includeXmlDeclaration = options.includeXmlDeclaration,
    ).write(drawCommands)
    return ExportArtifact(
        value = svg,
        mimeType = "image/svg+xml",
        widthPx = outputSize.width.roundToInt(),
        heightPx = outputSize.height.roundToInt(),
        diagnostics = diagnostics,
    )
}

private fun scaleSize(
    bounds: Size,
    scale: ExportScale,
): Size {
    val width = bounds.width
    val height = bounds.height
    if (width <= 0f || height <= 0f) return bounds
    val factor = when (scale) {
        ExportScale.Intrinsic -> 1f
        is ExportScale.Factor -> scale.value
        is ExportScale.Width -> scale.px / width
        is ExportScale.Height -> scale.px / height
    }
    if (factor == 1f) return bounds
    return Size(width * factor, height * factor)
}

private fun resolveSvgBackground(
    renderedBackground: Color?,
    requested: ExportBackground,
): Color? =
    when (requested) {
        ExportBackground.Auto -> renderedBackground
        ExportBackground.Transparent -> null
        is ExportBackground.Solid -> requested.color
    }
