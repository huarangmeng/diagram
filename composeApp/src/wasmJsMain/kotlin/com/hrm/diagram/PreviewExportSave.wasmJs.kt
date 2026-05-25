package com.hrm.diagram

actual suspend fun savePreviewExport(
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
): SavedPreviewFile {
    savePreviewExportHex(fileName, mimeType, bytes.toHexPayload())
    return SavedPreviewFile(
        fileName = fileName,
        locationDescription = "browser-download:$fileName",
    )
}

private fun ByteArray.toHexPayload(): String = buildString(size * 2) {
    for (byte in this@toHexPayload) {
        append(byte.toInt().and(0xFF).toString(16).padStart(2, '0'))
    }
}

external fun savePreviewExportHex(
    fileName: String,
    mimeType: String,
    hexPayload: String,
)
