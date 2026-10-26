@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hrm.diagram.render.export

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.export.ExportBackground
import com.hrm.diagram.core.export.ExportScale
import com.hrm.diagram.core.export.RasterExportOptions
import com.hrm.diagram.core.export.exportPng
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlinx.cinterop.*
import kotlinx.coroutines.test.runTest
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.ImageIO.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacosPipelineRasterTest {
    @Test
    fun default_chinese_and_multiline_labels_keep_every_glyph_despite_heuristic_bounds() = runTest {
        val samples = listOf(
            Triple(SourceLanguage.MERMAID, "pie\ntitle 中文测试\n\"A\" : 1\n", "中文测试"),
            Triple(SourceLanguage.DOT, "digraph { a [shape=ellipse,label=\"中文测试\\n完整文字\"]; }\n", "中文测试\n完整文字"),
        )
        for ((language, source, expectedText) in samples) {
            val session = Diagram.session(language)
            try {
                source.chunked(5).forEach { session.append(it) }
                val snapshot = session.finish()
                assertTrue(snapshot.diagnostics.none { it.severity == Severity.ERROR })
                val rendered = snapshot.prepareExport(ExportBackground.Transparent)
                val command = rendered.drawCommands.filterIsInstance<DrawCommand.DrawText>().single { it.text == expectedText }
                val measured = assertNotNull(command.measuredBounds)
                assertTrue(measured.size.width < command.font.sizeSp * 4, "Natural default heuristic must underestimate four CJK glyphs")
                if (language == SourceLanguage.MERMAID) assertEquals(null, command.maxWidth)
                // 原始生产命令完全不变；去掉其他图元只为像素隔离，避免节点背景掩盖丢字。
                val actualFrame = rendered.copy(drawCommands = listOf(command))
                val referenceFrame = rendered.copy(drawCommands = listOf(command.copy(measuredBounds = null)))
                val actualBytes = actualFrame.exportPng().value
                val referenceBytes = referenceFrame.exportPng().value
                val actual = decodePixels(actualBytes)
                val reference = decodePixels(referenceBytes)
                kotlin.test.assertContentEquals(reference.rgba, actual.rgba, "Heuristic geometry must not discard any glyph or line")
                // 独立 CoreText 大容器确认全串和各行的真实范围，不依赖生产 helper 或像素数量猜测。
                CFStringCreateWithCString(null, "Helvetica", kCFStringEncodingUTF8).owned { name ->
                    CTFontCreateWithName(name, command.font.sizeSp.toDouble(), null).owned { base ->
                        val traits = (if (command.font.weight >= 600) kCTFontBoldTrait else 0u) or (if (command.font.italic) kCTFontItalicTrait else 0u)
                        val styled = CTFontCreateCopyWithSymbolicTraits(base, 0.0, null, traits, kCTFontBoldTrait or kCTFontItalicTrait)
                        try {
                            CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr).owned { attributes ->
                                CFDictionarySetValue(attributes, kCTFontAttributeName, styled ?: base)
                                CFStringCreateWithCString(null, expectedText, kCFStringEncodingUTF8).owned { text ->
                                    CFAttributedStringCreate(null, text, attributes).owned { attributed ->
                                        CTFramesetterCreateWithAttributedString(attributed).owned { setter ->
                                            val frameWidth = command.maxWidth?.toDouble() ?: 1024.0
                                            CGPathCreateWithRect(CGRectMake(0.0, 0.0, frameWidth, 1024.0), null).owned { path ->
                                                CTFramesetterCreateFrame(setter, CFRangeMake(0, 0), path, null).owned { frame ->
                                                    CTFrameGetVisibleStringRange(frame).useContents {
                                                        assertEquals(0L, location)
                                                        assertEquals(expectedText.length.toLong(), length, "Every UTF-16 character must fit")
                                                    }
                                                    val lines = CTFrameGetLines(frame)
                                                    val lineCount = CFArrayGetCount(lines).toInt()
                                                    assertEquals(expectedText.count { it == '\n' } + 1, lineCount)
                                                    val inkRows = (0 until actual.height).map { y ->
                                                        (0 until actual.width).any { x -> (actual.rgba[(y * actual.width + x) * 4 + 3].toInt() and 255) > 60 }
                                                    }
                                                    val bands = mutableListOf<IntRange>()
                                                    var start = -1
                                                    for (y in inkRows.indices) {
                                                        if (inkRows[y] && start < 0) start = y
                                                        if (start >= 0 && (!inkRows[y] || y == inkRows.lastIndex)) {
                                                            bands += start until if (inkRows[y]) y + 1 else y
                                                            start = -1
                                                        }
                                                    }
                                                    assertEquals(lineCount, bands.size, "Every native line must produce a distinct visible row band")
                                                    for (i in 0 until lineCount) {
                                                        val line: CTLineRef = checkNotNull(CFArrayGetValueAtIndex(lines, i.toLong())).reinterpret()
                                                        val range = CTLineGetStringRange(line).useContents { location.toInt() until (location + length).toInt() }
                                                        val characterCount = range.count { expectedText[it] != '\n' }
                                                        assertEquals(4, characterCount, "Each line must contain all four Chinese characters")
                                                        val runs = CTLineGetGlyphRuns(line)
                                                        val glyphCharacters = mutableSetOf<Int>()
                                                        for (runIndex in 0 until CFArrayGetCount(runs)) {
                                                            val run: CTRunRef = checkNotNull(CFArrayGetValueAtIndex(runs, runIndex)).reinterpret()
                                                            val count = CTRunGetGlyphCount(run)
                                                            memScoped {
                                                                val indices = allocArray<LongVar>(count)
                                                                val glyphs = allocArray<UShortVar>(count)
                                                                CTRunGetStringIndices(run, CFRangeMake(0, 0), indices)
                                                                CTRunGetGlyphs(run, CFRangeMake(0, 0), glyphs)
                                                                for (glyph in 0 until count.toInt()) {
                                                                    val character = indices[glyph].toInt()
                                                                    if (character in range && expectedText[character] != '\n') {
                                                                        assertTrue(glyphs[glyph] != 0.toUShort(), "Missing native glyph for ${expectedText[character]}")
                                                                        glyphCharacters += character
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        assertEquals(range.filter { expectedText[it] != '\n' }.toSet(), glyphCharacters,
                                                            "Every Chinese character must retain its own CoreText glyph; newline glyphs are not visible text")
                                                        val advance = CTLineGetTypographicBounds(line, null, null, null)
                                                        val left = when (command.anchorX) {
                                                            com.hrm.diagram.core.draw.TextAnchorX.Start -> command.origin.x.toDouble()
                                                            com.hrm.diagram.core.draw.TextAnchorX.Center -> command.origin.x - advance / 2
                                                            com.hrm.diagram.core.draw.TextAnchorX.End -> command.origin.x - advance
                                                        } - rendered.bounds.left
                                                        for (character in 0 until characterCount) {
                                                            val x0 = (left + character * advance / characterCount).toInt().coerceIn(0, actual.width)
                                                            val x1 = (left + (character + 1) * advance / characterCount).toInt().coerceIn(x0, actual.width)
                                                            val visible = bands[i].sumOf { y -> (x0 until x1).count { x ->
                                                                (actual.rgba[(y * actual.width + x) * 4 + 3].toInt() and 255) > 60
                                                            } }
                                                            assertTrue(visible > 10, "Missing native glyph ${expectedText[range.first + character]} in line $i")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } finally { styled?.let { CFRelease(it) } }
                    }
                }
                assertEquals(command, snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().single { it.text == expectedText })
            } finally { session.close() }
        }
    }

    private data class Pixels(val width: Int, val height: Int, val rgba: ByteArray)

    private fun decodePixels(bytes: ByteArray): Pixels = bytes.usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()).owned { data ->
            CGImageSourceCreateWithData(data, null).owned { source ->
                CGImageSourceCreateImageAtIndex(source, 0u, null).owned { image ->
                    val width = CGImageGetWidth(image).toInt()
                    val height = CGImageGetHeight(image).toInt()
                    val rgba = ByteArray(width * height * 4)
                    CGColorSpaceCreateWithName(kCGColorSpaceSRGB).owned { space ->
                        rgba.usePinned { pixels ->
                            CGBitmapContextCreate(pixels.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big).owned { context ->
                                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
                            }
                        }
                    }
                    Pixels(width, height, rgba)
                }
            }
        }
    }

    private inline fun <T : CPointed, R> CPointer<T>?.owned(block: (CPointer<T>) -> R): R {
        val value = checkNotNull(this)
        try { return block(value) } finally { CFRelease(value) }
    }

    @Test
    fun all_languages_stream_through_parser_layout_and_export_with_visible_text() = runTest {
        val samples = listOf(
            SourceLanguage.MERMAID to "flowchart TD\nA[Alpha] --> B[Beta]\n",
            SourceLanguage.PLANTUML to "@startuml\ncomponent Alpha\ncomponent Beta\nAlpha --> Beta\n@enduml\n",
            SourceLanguage.DOT to "digraph { a [label=\"Alpha\"]; b [label=\"Beta\"]; a -> b; }\n",
        )
        for ((language, source) in samples) {
            val session = Diagram.session(language)
            try {
                source.chunked(7).forEach { session.append(it) }
                val snapshot = session.finish()
                assertTrue(snapshot.diagnostics.none { it.severity == Severity.ERROR }, "$language: ${snapshot.diagnostics}")
                assertNotNull(snapshot.laidOut)
                val rendered = snapshot.prepareExport(ExportBackground.Transparent)
                val texts = rendered.drawCommands.filterIsInstance<DrawCommand.DrawText>()
                assertTrue(texts.isNotEmpty(), "$language must produce text commands")
                texts.forEach { assertNotNull(it.measuredBounds, "Production measure stage must fill text bounds") }
                val bytes = snapshot.toPng(RasterExportOptions(scale = ExportScale.Width(320), background = ExportBackground.Transparent))
                bytes.usePinned { pinned ->
                    val data = checkNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()))
                    try {
                        val imageSource = checkNotNull(CGImageSourceCreateWithData(data, null))
                        try {
                            val image = checkNotNull(CGImageSourceCreateImageAtIndex(imageSource, 0u, null))
                            try {
                                val width = CGImageGetWidth(image).toInt()
                                val height = CGImageGetHeight(image).toInt()
                                assertEquals(320, width)
                                val space = checkNotNull(CGColorSpaceCreateWithName(kCGColorSpaceSRGB))
                                try {
                                    val pixels = ByteArray(width * height * 4)
                                    pixels.usePinned { pixelBuffer ->
                                        val context = checkNotNull(CGBitmapContextCreate(pixelBuffer.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                                            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
                                        try {
                                            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
                                        } finally {
                                            CGContextRelease(context)
                                        }
                                    }
                                    val scale = width / rendered.bounds.size.width
                                    for (text in texts) {
                                        val box = assertNotNull(text.measuredBounds)
                                        val left = ((box.left - rendered.bounds.left) * scale).toInt().coerceIn(0, width)
                                        val right = ((box.right - rendered.bounds.left) * scale).toInt().coerceIn(left, width)
                                        val top = ((box.top - rendered.bounds.top) * scale).toInt().coerceIn(0, height)
                                        val bottom = ((box.bottom - rendered.bounds.top) * scale).toInt().coerceIn(top, height)
                                        val visible = (top until bottom).sumOf { y -> (left until right).count { x ->
                                            val i = (y * width + x) * 4
                                            (pixels[i + 3].toInt() and 255) > 30 && (pixels[i].toInt() and 255) < 100
                                        } }
                                        assertTrue(visible > 5, "$language: text '${text.text}' must paint dark pixels inside its measured box")
                                    }
                                } finally {
                                    CGColorSpaceRelease(space)
                                }
                            } finally {
                                CGImageRelease(image)
                            }
                        } finally {
                            CFRelease(imageSource)
                        }
                    } finally {
                        CFRelease(data)
                    }
                }
            } finally {
                session.close()
            }
        }
    }
}
