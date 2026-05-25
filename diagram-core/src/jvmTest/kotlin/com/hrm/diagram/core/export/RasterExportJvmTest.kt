package com.hrm.diagram.core.export

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RasterExportJvmTest {
    @Test
    fun export_png_returns_valid_image() {
        val rendered = sampleRenderedDiagram()

        val artifact = runSuspend {
            rendered.exportPng(
                RasterExportOptions(
                    scale = ExportScale.Width(200),
                    background = ExportBackground.Transparent,
                ),
            )
        }

        assertEquals("image/png", artifact.mimeType)
        assertEquals(200, artifact.widthPx)
        assertEquals(100, artifact.heightPx)
        assertTrue(artifact.value.isNotEmpty())
        val image = ImageIO.read(ByteArrayInputStream(artifact.value))
        assertNotNull(image)
        assertEquals(200, image.width)
        assertEquals(100, image.height)
    }

    @Test
    fun export_jpeg_uses_opaque_background_and_warns_on_transparent_input() {
        val rendered = sampleRenderedDiagram(background = null)

        val artifact = runSuspend {
            rendered.exportJpeg(
                JpegExportOptions(
                    scale = ExportScale.Width(180),
                    background = ExportBackground.Transparent,
                    quality = 82,
                ),
            )
        }

        assertEquals("image/jpeg", artifact.mimeType)
        assertEquals(180, artifact.widthPx)
        assertEquals(90, artifact.heightPx)
        assertTrue(artifact.value.isNotEmpty())
        assertTrue(artifact.diagnostics.any { it.code == "EXPORT-W001" })
        val image = ImageIO.read(ByteArrayInputStream(artifact.value))
        assertNotNull(image)
        assertEquals(180, image.width)
        assertEquals(90, image.height)
    }

    private fun sampleRenderedDiagram(background: Color? = Color.White): RenderedDiagram =
        RenderedDiagram(
            bounds = Rect(Point(10f, 20f), Size(100f, 50f)),
            drawCommands = listOf(
                DrawCommand.FillRect(
                    rect = Rect(Point(10f, 20f), Size(100f, 50f)),
                    color = Color.Black,
                ),
            ),
            background = background,
        )

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return outcome!!.getOrThrow()
    }
}
