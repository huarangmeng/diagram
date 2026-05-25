package com.hrm.diagram

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

actual suspend fun savePreviewExport(
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
): SavedPreviewFile {
    val baseDirectory = preferredDesktopExportDirectory()
    Files.createDirectories(baseDirectory)
    val target = baseDirectory.resolve(fileName)
    Files.write(target, bytes)
    return SavedPreviewFile(
        fileName = fileName,
        locationDescription = target.toAbsolutePath().toString(),
    )
}

private fun preferredDesktopExportDirectory(): Path {
    val home = System.getProperty("user.home") ?: return Paths.get(System.getProperty("java.io.tmpdir"), "diagram-preview")
    val downloads = Paths.get(home, "Downloads")
    return if (Files.exists(downloads) || Files.isDirectory(downloads.parent)) {
        downloads.resolve("diagram-preview")
    } else {
        Paths.get(System.getProperty("java.io.tmpdir"), "diagram-preview")
    }
}
