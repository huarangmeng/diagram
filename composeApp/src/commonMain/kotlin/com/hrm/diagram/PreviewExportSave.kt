package com.hrm.diagram

data class SavedPreviewFile(
    val fileName: String,
    val locationDescription: String,
)

expect suspend fun savePreviewExport(
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
): SavedPreviewFile
