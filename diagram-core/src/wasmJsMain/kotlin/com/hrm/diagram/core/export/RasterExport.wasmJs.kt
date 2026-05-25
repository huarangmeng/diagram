package com.hrm.diagram.core.export

actual suspend fun RenderedDiagram.exportPng(
    options: RasterExportOptions,
): ExportArtifact<ByteArray> = unsupportedRasterExport(
    mimeType = "image/png",
    platformName = "Wasm",
    scale = options.scale,
    background = options.background,
    opaqueRequired = false,
)

actual suspend fun RenderedDiagram.exportJpeg(
    options: JpegExportOptions,
): ExportArtifact<ByteArray> = unsupportedRasterExport(
    mimeType = "image/jpeg",
    platformName = "Wasm",
    scale = options.scale,
    background = options.background,
    opaqueRequired = true,
)
