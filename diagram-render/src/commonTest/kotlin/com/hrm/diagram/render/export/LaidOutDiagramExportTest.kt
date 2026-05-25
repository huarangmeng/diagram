package com.hrm.diagram.render.export

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.export.ExportBackground
import com.hrm.diagram.core.export.ExportScale
import com.hrm.diagram.core.export.JpegExportOptions
import com.hrm.diagram.core.export.RasterExportOptions
import com.hrm.diagram.core.export.SvgExportOptions
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Participant
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.PieSlice
import com.hrm.diagram.core.ir.SequenceMessage
import com.hrm.diagram.core.ir.TimeItem
import com.hrm.diagram.core.ir.TimeRange
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.ir.TimeTrack
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.TreeNode
import com.hrm.diagram.core.theme.DiagramTheme
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.LaidOutDiagram
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaidOutDiagramExportTest {
    @Test
    fun prepare_export_renders_graph_ir_with_theme_background() {
        val graph = GraphIR(
            nodes = listOf(Node(id = NodeId("a"), label = RichLabel.of("Alpha"))),
            sourceLanguage = SourceLanguage.DOT,
        )
        val laidOut = LaidOutDiagram(
            source = graph,
            nodePositions = mapOf(NodeId("a") to Rect(Point(20f, 16f), Size(120f, 48f))),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(180f, 96f)),
        )

        val rendered = laidOut.prepareExport(theme = DiagramTheme.Dark)

        assertEquals(laidOut.bounds, rendered.bounds)
        assertEquals(DiagramTheme.Dark.background, rendered.background)
        assertTrue(rendered.drawCommands.isNotEmpty(), "expected rendered graph commands")
    }

    @Test
    fun laid_out_to_svg_exports_graph_ir_frame() {
        val graph = GraphIR(
            nodes = listOf(Node(id = NodeId("a"), label = RichLabel.of("Alpha"))),
            sourceLanguage = SourceLanguage.DOT,
        )
        val laidOut = LaidOutDiagram(
            source = graph,
            nodePositions = mapOf(NodeId("a") to Rect(Point(20f, 16f), Size(120f, 48f))),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(180f, 96f)),
        )

        val svg = laidOut.toSvg(
            theme = DiagramTheme.Default,
            options = SvgExportOptions(
                scale = ExportScale.Width(360),
                background = ExportBackground.Transparent,
                includeXmlDeclaration = false,
            ),
        )

        assertTrue(svg.startsWith("<svg "), svg)
        assertTrue("Alpha" in svg, svg)
        assertTrue("width=\"360\"" in svg, svg)
        assertTrue("viewBox=\"0 0 180 96\"" in svg, svg)
    }

    @Test
    fun prepare_export_renders_pie_tree_sequence_and_time_series() {
        val pieRendered = samplePieLaidOut().prepareExport(theme = DiagramTheme.Default)
        val treeRendered = sampleTreeLaidOut().prepareExport(theme = DiagramTheme.Default)
        val sequenceRendered = sampleSequenceLaidOut().prepareExport(theme = DiagramTheme.Default)
        val timeSeriesRendered = sampleTimeSeriesLaidOut().prepareExport(
            theme = DiagramTheme.Dark,
            background = ExportBackground.Solid(Color.White),
        )

        assertTrue(pieRendered.drawCommands.isNotEmpty(), "pie one-shot export should render commands")
        assertTrue(treeRendered.drawCommands.isNotEmpty(), "tree one-shot export should render commands")
        assertTrue(sequenceRendered.drawCommands.isNotEmpty(), "sequence one-shot export should render commands")
        assertTrue(timeSeriesRendered.drawCommands.isNotEmpty(), "time-series one-shot export should render commands")
        assertEquals(Color.White, timeSeriesRendered.background)
    }

    @Test
    fun laid_out_to_png_exports_graph_ir_frame() = runTest {
        val laidOut = sampleGraphLaidOut()

        val png = laidOut.toPng(
            theme = DiagramTheme.Default,
            options = RasterExportOptions(
                scale = ExportScale.Width(240),
                background = ExportBackground.Solid(Color.White),
            ),
        )

        assertTrue(png.isNotEmpty())
    }

    @Test
    fun laid_out_to_jpeg_exports_graph_ir_frame() = runTest {
        val laidOut = sampleGraphLaidOut()

        val jpeg = laidOut.toJpeg(
            theme = DiagramTheme.Default,
            options = JpegExportOptions(
                scale = ExportScale.Width(240),
                quality = 82,
            ),
        )

        assertTrue(jpeg.isNotEmpty())
    }

    private fun sampleGraphLaidOut(): LaidOutDiagram {
        val graph = GraphIR(
            nodes = listOf(Node(id = NodeId("a"), label = RichLabel.of("Alpha"))),
            sourceLanguage = SourceLanguage.DOT,
        )
        return LaidOutDiagram(
            source = graph,
            nodePositions = mapOf(NodeId("a") to Rect(Point(20f, 16f), Size(120f, 48f))),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(180f, 96f)),
        )
    }

    private fun samplePieLaidOut(): LaidOutDiagram {
        val pie = PieIR(
            slices = listOf(
                PieSlice(RichLabel.of("A"), 4.0),
                PieSlice(RichLabel.of("B"), 6.0),
            ),
            title = "Pie",
            sourceLanguage = SourceLanguage.MERMAID,
        )
        return LaidOutDiagram(
            source = pie,
            nodePositions = mapOf(
                NodeId("pie:title") to Rect(Point(20f, 20f), Size(40f, 18f)),
                NodeId("pie:plot") to Rect(Point(20f, 48f), Size(240f, 240f)),
                NodeId("pie:legend:0") to Rect(Point(278f, 56f), Size(80f, 20f)),
                NodeId("pie:legend:1") to Rect(Point(278f, 84f), Size(80f, 20f)),
            ),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(380f, 320f)),
        )
    }

    private fun sampleTreeLaidOut(): LaidOutDiagram {
        val root = TreeNode(
            id = NodeId("root"),
            label = RichLabel.of("Root"),
            children = listOf(TreeNode(id = NodeId("child"), label = RichLabel.of("Child"))),
        )
        return LaidOutDiagram(
            source = TreeIR(root = root, sourceLanguage = SourceLanguage.MERMAID),
            nodePositions = mapOf(
                NodeId("root") to Rect(Point(120f, 40f), Size(120f, 48f)),
                NodeId("child") to Rect(Point(280f, 120f), Size(120f, 48f)),
            ),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(440f, 220f)),
        )
    }

    private fun sampleSequenceLaidOut(): LaidOutDiagram {
        val a = Participant(NodeId("a"), RichLabel.of("Alice"))
        val b = Participant(NodeId("b"), RichLabel.of("Bob"))
        return LaidOutDiagram(
            source = SequenceIR(
                participants = listOf(a, b),
                messages = listOf(SequenceMessage(from = a.id, to = b.id, label = RichLabel.of("hi"))),
                sourceLanguage = SourceLanguage.MERMAID,
            ),
            nodePositions = mapOf(
                a.id to Rect(Point(40f, 20f), Size(96f, 36f)),
                b.id to Rect(Point(240f, 20f), Size(96f, 36f)),
            ),
            edgeRoutes = listOf(
                EdgeRoute(
                    from = a.id,
                    to = b.id,
                    points = listOf(Point(88f, 110f), Point(288f, 110f)),
                ),
            ),
            clusterRects = emptyMap(),
            bounds = Rect(Point.Zero, Size(380f, 220f)),
        )
    }

    private fun sampleTimeSeriesLaidOut(): LaidOutDiagram {
        val track = TimeTrack(NodeId("backend"), RichLabel.of("Backend"))
        val item = TimeItem(
            id = NodeId("task"),
            label = RichLabel.of("Implement"),
            range = TimeRange(0, 10),
            trackId = track.id,
            payload = mapOf("gantt.tags" to "active"),
        )
        return LaidOutDiagram(
            source = TimeSeriesIR(
                tracks = listOf(track),
                items = listOf(item),
                range = TimeRange(0, 10),
                title = "Roadmap",
                sourceLanguage = SourceLanguage.MERMAID,
            ),
            nodePositions = mapOf(
                NodeId("gantt:title") to Rect(Point(18f, 18f), Size(72f, 18f)),
                NodeId("gantt:axis") to Rect(Point(180f, 60f), Size(240f, 70f)),
                NodeId("gantt:track:${track.id.value}") to Rect(Point(18f, 100f), Size(120f, 18f)),
                NodeId("gantt:itemLabel:${item.id.value}") to Rect(Point(18f, 128f), Size(120f, 18f)),
                NodeId("gantt:item:${item.id.value}") to Rect(Point(200f, 124f), Size(140f, 24f)),
            ),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(460f, 200f)),
        )
    }
}
