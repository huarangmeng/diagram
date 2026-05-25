package com.hrm.diagram.render.export

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.export.ExportBackground
import com.hrm.diagram.core.export.ExportScale
import com.hrm.diagram.core.export.SvgExportOptions
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagramSnapshotExportTest {
    @Test
    fun prepare_export_keeps_snapshot_bounds_and_draw_commands() {
        val session = Diagram.session(SourceLanguage.DOT)
        try {
            session.append("digraph { a [label=\"Alpha\"]; }\n")
            val snapshot = session.finish()

            val rendered = snapshot.prepareExport(
                background = ExportBackground.Solid(Color.White),
            )

            val laidOut = assertNotNull(snapshot.laidOut)
            assertEquals(laidOut.bounds, rendered.bounds)
            assertEquals(snapshot.drawCommands, rendered.drawCommands)
            assertEquals(Color.White, rendered.background)
        } finally {
            session.close()
        }
    }

    @Test
    fun snapshot_to_svg_exports_current_frame() {
        val session = Diagram.session(SourceLanguage.DOT)
        try {
            session.append("digraph { a [label=\"Alpha\"]; a -> b [label=\"edge\"]; }\n")
            val snapshot = session.finish()

            val svg = snapshot.toSvg(
                SvgExportOptions(
                    scale = ExportScale.Width(320),
                    background = ExportBackground.Transparent,
                    includeXmlDeclaration = false,
                ),
            )

            assertTrue(svg.startsWith("<svg "), svg)
            assertTrue("Alpha" in svg, svg)
            assertTrue("edge" in svg, svg)
            assertTrue("viewBox=" in svg, svg)
            assertTrue("width=\"320\"" in svg, svg)
        } finally {
            session.close()
        }
    }
}
