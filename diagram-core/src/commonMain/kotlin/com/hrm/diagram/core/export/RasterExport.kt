package com.hrm.diagram.core.export

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import kotlin.math.roundToInt

/**
 * Export this rendered frame as PNG bytes.
 *
 * Minimal usage:
 * ```kotlin
 * val artifact = rendered.exportPng()
 * val png = artifact.value
 * ```
 */
@DiagramApi
expect suspend fun RenderedDiagram.exportPng(
    options: RasterExportOptions = RasterExportOptions(),
): ExportArtifact<ByteArray>

/**
 * Export this rendered frame as JPEG bytes.
 *
 * Minimal usage:
 * ```kotlin
 * val artifact = rendered.exportJpeg(JpegExportOptions(quality = 85))
 * val jpeg = artifact.value
 * ```
 */
@DiagramApi
expect suspend fun RenderedDiagram.exportJpeg(
    options: JpegExportOptions = JpegExportOptions(),
): ExportArtifact<ByteArray>

internal data class RasterExportPlan(
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
    val background: Color?,
    val diagnostics: List<Diagnostic>,
)

internal fun RenderedDiagram.planRasterExport(
    scale: ExportScale,
    background: ExportBackground,
    opaqueRequired: Boolean,
): RasterExportPlan {
    val baseWidth = bounds.size.width
    val baseHeight = bounds.size.height
    val factor = when {
        baseWidth <= 0f || baseHeight <= 0f -> 1f
        scale is ExportScale.Intrinsic -> 1f
        scale is ExportScale.Factor -> scale.value
        scale is ExportScale.Width -> scale.px / baseWidth
        scale is ExportScale.Height -> scale.px / baseHeight
        else -> 1f
    }
    val outputWidth = maxOf(1, (baseWidth * factor).roundToInt())
    val outputHeight = maxOf(1, (baseHeight * factor).roundToInt())
    val diagnostics = ArrayList<Diagnostic>()
    var resolvedBackground = when (background) {
        ExportBackground.Auto -> this.background
        ExportBackground.Transparent -> null
        is ExportBackground.Solid -> background.color
    }
    if (opaqueRequired && (resolvedBackground == null || resolvedBackground.a == 0)) {
        resolvedBackground = Color.White
        diagnostics += Diagnostic(
            severity = Severity.WARNING,
            code = "EXPORT-W001",
            message = "Opaque raster export requires a solid background; exporter falls back to white.",
        )
    }
    return RasterExportPlan(
        widthPx = outputWidth,
        heightPx = outputHeight,
        scale = factor,
        background = resolvedBackground,
        diagnostics = diagnostics,
    )
}

internal fun RenderedDiagram.unsupportedRasterExport(
    mimeType: String,
    platformName: String,
    scale: ExportScale,
    background: ExportBackground,
    opaqueRequired: Boolean,
): ExportArtifact<ByteArray> {
    val plan = planRasterExport(scale = scale, background = background, opaqueRequired = opaqueRequired)
    return ExportArtifact(
        value = ByteArray(0),
        mimeType = mimeType,
        widthPx = plan.widthPx,
        heightPx = plan.heightPx,
        diagnostics = plan.diagnostics + Diagnostic(
            severity = Severity.WARNING,
            code = "EXPORT-W001",
            message = "$platformName raster export is not implemented yet on this platform.",
        ),
    )
}

internal fun fallbackRasterExport(
    plan: RasterExportPlan,
    mimeType: String,
    message: String,
): ExportArtifact<ByteArray> =
    ExportArtifact(
        value = fallbackRasterBytes(mimeType),
        mimeType = mimeType,
        widthPx = plan.widthPx,
        heightPx = plan.heightPx,
        diagnostics = plan.diagnostics + Diagnostic(
            severity = Severity.WARNING,
            code = "EXPORT-W001",
            message = message,
        ),
    )

private fun fallbackRasterBytes(mimeType: String): ByteArray =
    when (mimeType) {
        "image/png" -> byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        "image/jpeg" -> byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        else -> ByteArray(1) { 0 }
    }
