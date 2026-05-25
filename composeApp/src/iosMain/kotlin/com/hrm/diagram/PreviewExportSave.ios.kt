@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hrm.diagram

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

actual suspend fun savePreviewExport(
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
): SavedPreviewFile {
    val directory = "${NSHomeDirectory()}/Documents/diagram-preview"
    NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
    val target = "$directory/$fileName"
    writeBytesToFile(target, bytes)
    return SavedPreviewFile(
        fileName = fileName,
        locationDescription = target,
    )
}

private fun writeBytesToFile(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb")
    check(file != null) {
        "Unable to save preview export to $path"
    }
    try {
        if (bytes.isNotEmpty()) {
            val written = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
            }
            check(written == bytes.size.toULong()) {
                "Short write while saving preview export to $path"
            }
        }
    } finally {
        fclose(file)
    }
}
