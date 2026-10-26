@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hrm.diagram.core.export

import com.hrm.diagram.core.draw.*
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.ImageIO.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class RasterExportMacosTest {
    @Test
    fun png_preserves_top_left_coordinates_scale_alpha_and_z_order() {
        val frame = RenderedDiagram(
            bounds = rect(10f, 20f, 100f, 60f),
            drawCommands = listOf(
                DrawCommand.FillRect(rect(15f, 25f, 10f, 10f), Color(0xff0000ff.toInt()), z = 2),
                DrawCommand.FillRect(rect(15f, 25f, 10f, 10f), Color.Black, z = 1),
                DrawCommand.FillRect(rect(60f, 55f, 10f, 10f), Color(0x80ff0000.toInt())),
            ),
        )
        val artifact = runSuspend { frame.exportPng(RasterExportOptions(ExportScale.Width(200), ExportBackground.Transparent)) }
        assertEquals("image/png", artifact.mimeType)
        assertEquals(200, artifact.widthPx)
        assertEquals(120, artifact.heightPx)
        assertTrue(artifact.diagnostics.isEmpty())
        val image = decode(artifact.value)
        assertEquals(200, image.width)
        assertEquals(120, image.height)
        assertEquals(0, image.channel(2, 2, 3))
        assertEquals(255, image.channel(15, 15, 2))
        assertEquals(255, image.channel(15, 15, 3))
        assertEquals(0, image.channel(15, 105, 3), "Y axis must not be inverted")
        assertTrue(image.channel(105, 75, 3) in 127..129)
        assertTrue(image.channel(105, 75, 0) in 127..129, "RGBA decoder stores premultiplied red")
    }

    @Test
    fun nested_transform_clip_rotation_and_state_restoration_have_visible_pixels() {
        val frame = RenderedDiagram(
            rect(0f, 0f, 120f, 80f),
            listOf(
                DrawCommand.Group(
                    listOf(DrawCommand.Clip(rect(0f, 0f, 10f, 10f), listOf(DrawCommand.FillRect(rect(-5f, -5f, 30f, 30f), Color.Black)))),
                    Transform(translate = Point(20f, 10f), scale = 2f),
                ),
                DrawCommand.Group(
                    listOf(DrawCommand.FillRect(rect(0f, 0f, 12f, 6f), Color.Black)),
                    Transform(translate = Point(70f, 20f), rotateDeg = 90f),
                ),
                DrawCommand.FillRect(rect(100f, 60f, 5f, 5f), Color.Black),
                DrawCommand.Hyperlink("https://example.invalid/", rect(0f, 0f, 120f, 80f)),
            ),
        )
        val image = decode(runSuspend { frame.exportPng() }.value)
        assertEquals(255, image.channel(25, 15, 3))
        assertEquals(0, image.channel(45, 15, 3), "Group clip must follow its transform")
        assertEquals(255, image.channel(67, 26, 3), "Positive rotation is clockwise")
        assertEquals(0, image.channel(74, 26, 3))
        assertEquals(255, image.channel(102, 62, 3), "Clip and transform must not leak")
        assertEquals(0, image.channel(5, 5, 3), "Hyperlink must not paint a hit rectangle")
    }

    @Test
    fun paths_round_corners_dash_caps_joins_and_all_arrow_heads_are_drawn() {
        val commands = mutableListOf<DrawCommand>(
            DrawCommand.FillRect(rect(5f, 5f, 30f, 20f), Color.Black, corner = 8f),
            DrawCommand.StrokeRect(rect(45f, 5f, 30f, 20f), Stroke(2f, listOf(4f, 4f), Cap.Round, Join.Round), Color.Black, corner = 4f),
            DrawCommand.FillPath(PathCmd(listOf(PathOp.MoveTo(Point(90f, 5f)), PathOp.LineTo(Point(110f, 5f)), PathOp.LineTo(Point(100f, 25f)), PathOp.Close)), Color.Black),
            DrawCommand.StrokePath(
                PathCmd(listOf(PathOp.MoveTo(Point(5f, 40f)), PathOp.QuadTo(Point(15f, 25f), Point(30f, 40f)), PathOp.CubicTo(Point(40f, 55f), Point(50f, 25f), Point(60f, 40f)))),
                Stroke(3f, cap = Cap.Square, join = Join.Bevel), Color.Black,
            ),
        )
        ArrowHead.entries.forEachIndexed { index, head ->
            val y = 65f + index * 20f
            commands += DrawCommand.DrawArrow(Point(20f, y), Point(90f, y), ArrowStyle(head, head, Color.Black, Stroke(2f, listOf(5f, 3f))))
        }
        val image = decode(runSuspend { RenderedDiagram(rect(0f, 0f, 130f, 250f), commands).exportPng() }.value)
        assertEquals(0, image.channel(5, 5, 3), "Rounded corner must not become square")
        assertEquals(255, image.channel(20, 15, 3))
        assertEquals(255, image.channel(100, 12, 3))
        assertTrue(image.ink(4, 26, 65, 56) > 70, "Quadratic and cubic strokes must survive")
        val dash = (49..71).map { image.channel(it, 5, 3) }
        assertTrue(dash.any { it > 150 } && dash.any { it < 30 }, "Dash gaps must remain visible")
        for (index in 1 until ArrowHead.entries.size) {
            val y = 65 + index * 20
            assertTrue(image.ink(80, y - 7, 97, y + 8) > 8, "Missing head ${ArrowHead.entries[index]}")
            assertTrue(image.ink(13, y - 7, 31, y + 8) > 8, "Missing tail ${ArrowHead.entries[index]}")
        }
    }

    @Test
    fun text_uses_measured_boxes_baseline_wrapping_unicode_and_font_traits() {
        val font = FontSpec("Helvetica", 20f)
        val commands = listOf(
            DrawCommand.DrawText("Hgj", Point(20f, 35f), font, Color.Black, measuredBounds = rect(20f, 12f, 70f, 30f)),
            DrawCommand.DrawText("Hello\n世界", Point(130f, 75f), font.copy(weight = 700, italic = true), Color.Black, maxWidth = 100f,
                anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle, measuredBounds = rect(80f, 45f, 100f, 60f)),
            DrawCommand.DrawText("Right", Point(270f, 15f), font.copy(family = "Menlo"), Color.Black,
                anchorX = TextAnchorX.End, anchorY = TextAnchorY.Top, measuredBounds = rect(180f, 15f, 90f, 35f)),
            DrawCommand.DrawText("Bottom", Point(270f, 140f), font, Color.Black,
                anchorX = TextAnchorX.End, anchorY = TextAnchorY.Bottom, measuredBounds = rect(180f, 105f, 90f, 35f)),
        )
        val frame = RenderedDiagram(rect(0f, 0f, 300f, 160f), commands)
        val image = decode(runSuspend { frame.exportPng() }.value)
        assertTrue(image.ink(20, 13, 90, 35) > 40, "Text must appear above its baseline")
        assertTrue(image.ink(20, 35, 90, 42) > 4, "Descenders must appear below baseline, not flipped")
        assertTrue(image.ink(80, 45, 180, 72) > 40, "First wrapped line must be visible")
        assertTrue(image.ink(80, 72, 180, 105) > 40, "Unicode second line must not be dropped")
        assertTrue(image.ink(200, 15, 270, 50) > 40, "Right anchor must place text at the box end")
        assertTrue(image.ink(180, 105, 270, 140) > 40)
        assertEquals(0, image.ink(0, 0, 15, 160), "Text must not leak to the canvas origin")
        val plain = decode(runSuspend { frame.copy(drawCommands = commands.map { it.copy(font = it.font.copy(weight = 400, italic = false)) }).exportPng() }.value)
        assertTrue(!plain.rgba.contentEquals(image.rgba), "Bold/italic must change actual pixels")
    }

    @Test
    fun legacy_text_without_measured_bounds_keeps_anchors_and_wrapped_content() {
        val font = FontSpec("Helvetica", 20f)
        val commands = listOf(
            DrawCommand.DrawText("Hgj", Point(20f, 35f), font, Color.Black),
            DrawCommand.DrawText("Hello world wrapped", Point(110f, 90f), font, Color.Black, maxWidth = 85f,
                anchorX = TextAnchorX.Center, anchorY = TextAnchorY.Middle),
            DrawCommand.DrawText("Right", Point(260f, 15f), font, Color.Black, anchorX = TextAnchorX.End, anchorY = TextAnchorY.Top),
            DrawCommand.DrawText("Bottom", Point(260f, 140f), font, Color.Black, anchorX = TextAnchorX.End, anchorY = TextAnchorY.Bottom),
        )
        val frame = RenderedDiagram(rect(0f, 0f, 300f, 160f), commands)
        val artifact = runSuspend { frame.exportPng() }
        assertTrue(artifact.diagnostics.isEmpty())
        val image = decode(artifact.value)
        assertTrue(image.ink(20, 12, 65, 35) > 40)
        assertTrue(image.ink(20, 35, 65, 42) > 4)
        assertTrue(image.ink(65, 55, 155, 90) > 40, "Wrapped text must retain first lines")
        assertTrue(image.ink(65, 90, 155, 130) > 40, "Wrapped text must retain later lines")
        assertTrue(image.ink(210, 15, 260, 45) > 40, "Missing bounds must not turn right anchor into start anchor")
        assertTrue(image.ink(190, 110, 260, 140) > 40)
        assertEquals(0, image.ink(270, 0, 300, 160))
    }

    @Test
    fun missing_font_trait_face_uses_the_real_base_font_without_losing_pixels() {
        // 不替换生产 CoreText API：必须先在 runner 真实字体上观察到合法 null。
        val candidates = listOf(
            "AppleColorEmoji" to "😀😃😄",
            "ZapfDingbats" to "✂✈✉",
            "Symbol" to "ΩΨΣ",
            "LastResort" to "abcd",
        )
        val unavailable = candidates.firstOrNull { (name, _) ->
            val cfName = checkNotNull(CFStringCreateWithCString(null, name, kCFStringEncodingUTF8))
            try {
                val font = checkNotNull(CTFontCreateWithName(cfName, 24.0, null))
                try {
                    val resolved = checkNotNull(CTFontCopyPostScriptName(font))
                    val sameFont = try {
                        memScoped {
                            val bufferSize = CFStringGetMaximumSizeForEncoding(CFStringGetLength(resolved), kCFStringEncodingUTF8) + 1
                            val buffer = allocArray<ByteVar>(bufferSize)
                            check(CFStringGetCString(resolved, buffer, bufferSize, kCFStringEncodingUTF8))
                            buffer.toKString() == name
                        }
                    } finally { CFRelease(resolved) }
                    val styled = CTFontCreateCopyWithSymbolicTraits(font, 0.0, null,
                        kCTFontBoldTrait or kCTFontItalicTrait, kCTFontBoldTrait or kCTFontItalicTrait)
                    if (styled != null) CFRelease(styled)
                    sameFont && styled == null
                } finally {
                    CFRelease(font)
                }
            } finally {
                CFRelease(cfName)
            }
        }
        val (family, text) = checkNotNull(unavailable) { "Runner must expose a real single-face font whose requested traits return null; do not skip or mock this case" }
        val command = DrawCommand.DrawText(text, Point(20f, 45f), FontSpec(family, 24f, 700, italic = true), Color.Black)
        val frame = RenderedDiagram(rect(0f, 0f, 200f, 80f), listOf(command))
        val styled = decode(runSuspend { frame.exportPng() }.value)
        val plain = decode(runSuspend { frame.copy(drawCommands = listOf(command.copy(font = command.font.copy(weight = 400, italic = false)))).exportPng() }.value)
        assertTrue(styled.ink(15, 10, 150, 65) > 30, "Fallback font must render its real glyphs")
        assertContentEquals(plain.rgba, styled.rgba, "Unavailable face must retain the base font, not omit text or choose a different family")
    }

    @Test
    fun icon_without_asset_keeps_svg_placeholder_and_diagnostic() {
        val artifact = runSuspend {
            RenderedDiagram(rect(0f, 0f, 40f, 40f), listOf(DrawCommand.DrawIcon("unknown:asset", rect(10f, 10f, 20f, 20f)))).exportPng()
        }
        val image = decode(artifact.value)
        assertTrue(image.ink(9, 9, 32, 32) > 10)
        assertEquals(0, image.channel(20, 20, 3))
        assertTrue(artifact.diagnostics.any { it.code == "EXPORT-W001" && "placeholder" in it.message })
    }

    @Test
    fun jpeg_is_opaque_preserves_background_and_quality_changes_decoded_error() {
        val commands = buildList {
            for (y in 0 until 96) for (x in 0 until 128) {
                val v = (x * 73 + y * 151 + x * y * 3) and 255
                add(DrawCommand.FillRect(rect(x.toFloat(), y.toFloat(), 1f, 1f), Color(0xff000000.toInt() or (v shl 16) or (((v * 3) and 255) shl 8) or ((v * 7) and 255))))
            }
        }
        val frame = RenderedDiagram(rect(0f, 0f, 160f, 120f), commands)
        val low = runSuspend { frame.exportJpeg(JpegExportOptions(background = ExportBackground.Transparent, quality = 15)) }
        val high = runSuspend { frame.exportJpeg(JpegExportOptions(background = ExportBackground.Transparent, quality = 95)) }
        assertEquals("image/jpeg", high.mimeType)
        assertTrue(high.diagnostics.any { it.code == "EXPORT-W001" })
        val lowImage = decode(low.value)
        val highImage = decode(high.value)
        assertEquals(160, highImage.width)
        assertEquals(120, highImage.height)
        assertTrue(highImage.rgba.indices.filter { it % 4 == 3 }.all { highImage.rgba[it].toInt() and 255 == 255 })
        for (c in 0..2) assertTrue(highImage.channel(150, 110, c) >= 250, "Transparent JPEG background must be white")
        val reference = decode(runSuspend { frame.exportPng(RasterExportOptions(background = ExportBackground.Solid(Color.White))) }.value)
        fun error(image: Pixels): Long = (0 until 96).sumOf { y -> (0 until 128).sumOf { x -> (0..2).sumOf { c -> abs(image.channel(x, y, c) - reference.channel(x, y, c)).toLong() } } }
        assertTrue(error(highImage) < error(lowImage), "Quality must improve decoded pixels, not merely file size")
        assertTrue(high.value.size > low.value.size)
        val background = decode(runSuspend { frame.exportJpeg(JpegExportOptions(background = ExportBackground.Solid(Color(0xff204060.toInt())))) }.value)
        listOf(32, 64, 96).forEachIndexed { c, value -> assertTrue(abs(background.channel(150, 110, c) - value) < 5) }
    }

    private fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(Point(x, y), Size(width, height))

    private data class Pixels(val width: Int, val height: Int, val rgba: ByteArray) {
        fun channel(x: Int, y: Int, channel: Int) = rgba[(y * width + x) * 4 + channel].toInt() and 255
        fun ink(left: Int, top: Int, right: Int, bottom: Int): Int =
            (top until bottom).sumOf { y -> (left until right).count { x -> channel(x, y, 3) > 30 } }
    }

    /** 独立解码最终 PNG/JPEG，不读取生产 context，检查编码后的真实像素。 */
    private fun decode(bytes: ByteArray): Pixels = bytes.usePinned { pinned ->
        val data = checkNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()))
        try {
            val source = checkNotNull(CGImageSourceCreateWithData(data, null))
            try {
                val image = checkNotNull(CGImageSourceCreateImageAtIndex(source, 0u, null))
                try {
                    val width = CGImageGetWidth(image).toInt()
                    val height = CGImageGetHeight(image).toInt()
                    val rgba = ByteArray(width * height * 4)
                    val space = checkNotNull(CGColorSpaceCreateWithName(kCGColorSpaceSRGB))
                    try {
                        rgba.usePinned { pixels ->
                            val context = checkNotNull(CGBitmapContextCreate(pixels.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
                            try {
                                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
                            } finally {
                                CGContextRelease(context)
                            }
                        }
                    } finally {
                        CGColorSpaceRelease(space)
                    }
                    Pixels(width, height, rgba)
                } finally {
                    CGImageRelease(image)
                }
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(data)
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome).getOrThrow()
    }
}
