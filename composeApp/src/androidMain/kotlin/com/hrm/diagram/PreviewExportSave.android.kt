package com.hrm.diagram

import android.content.Context
import android.os.Environment
import java.io.File

private object PreviewExportContextHolder {
    var appContext: Context? = null
}

internal fun installPreviewExportContext(context: Context) {
    PreviewExportContextHolder.appContext = context.applicationContext
}

actual suspend fun savePreviewExport(
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
): SavedPreviewFile {
    val context = requireNotNull(PreviewExportContextHolder.appContext) {
        "Preview export context is not installed"
    }
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: File(context.filesDir, "downloads")
    val directory = File(base, "diagram-preview").apply { mkdirs() }
    val target = File(directory, fileName)
    target.writeBytes(bytes)
    return SavedPreviewFile(
        fileName = fileName,
        locationDescription = target.absolutePath,
    )
}
