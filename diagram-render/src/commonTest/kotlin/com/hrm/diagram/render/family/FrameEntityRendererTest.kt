package com.hrm.diagram.render.family

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.ActivityBlock
import com.hrm.diagram.core.ir.ActivityIR
import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassNode
import com.hrm.diagram.core.ir.ClassRelation
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GaugeIR
import com.hrm.diagram.core.ir.GitCommit
import com.hrm.diagram.core.ir.GitGraphIR
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.ir.JourneyStage
import com.hrm.diagram.core.ir.JourneyStep
import com.hrm.diagram.core.ir.KanbanCard
import com.hrm.diagram.core.ir.KanbanColumn
import com.hrm.diagram.core.ir.KanbanIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.PieSlice
import com.hrm.diagram.core.ir.QuadrantChartIR
import com.hrm.diagram.core.ir.QuadrantPoint
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SankeyFlow
import com.hrm.diagram.core.ir.SankeyIR
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.SequenceMessage
import com.hrm.diagram.core.ir.Participant
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateNode
import com.hrm.diagram.core.ir.StateTransition
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.core.ir.TimeItem
import com.hrm.diagram.core.ir.TimeRange
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.ir.TimeTrack
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.TreeNode
import com.hrm.diagram.core.ir.WireBox
import com.hrm.diagram.core.ir.WireframeIR
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.core.ir.Axis
import com.hrm.diagram.core.ir.Series
import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.test.Test
import kotlin.test.assertTrue

class FrameEntityRendererTest {
    @Test
    fun emits_semantic_entities_for_non_graph_families() {
        assertKeys(PieIR(listOf(PieSlice(RichLabel.of("A"), 1.0), PieSlice(RichLabel.of("B"), 2.0)), sourceLanguage = SourceLanguage.MERMAID), "pie.slice", "pie.legend")
        assertKeys(TimeSeriesIR(listOf(TimeTrack(id("lane"))), listOf(TimeItem(id("task"), RichLabel.of("T"), TimeRange(0, 1), id("lane"), depends = listOf(id("prev")))), TimeRange(0, 1), sourceLanguage = SourceLanguage.MERMAID), "time-series.lane", "time-series.item", "time-series.dependency")
        assertKeys(XYChartIR(Axis(), Axis(), listOf(Series("S", SeriesKind.Line, listOf(1.0, 2.0), listOf(3.0, 4.0))), sourceLanguage = SourceLanguage.MERMAID), "xy.axis", "xy.point", "xy.legend")
        assertKeys(TreeIR(TreeNode(id("root"), children = listOf(TreeNode(id("child")))), sourceLanguage = SourceLanguage.MERMAID), "tree.node.root", "tree.edge.root-child", "tree.node.child")
        assertKeys(StructIR(StructNode.ObjectNode("root", listOf(StructNode.Scalar("name", "Ada"))), sourceLanguage = SourceLanguage.PLANTUML), "struct.root", "name")
    }

    @Test
    fun emits_semantic_entities_for_remaining_special_families() {
        assertKeys(SequenceIR(participants = listOf(Participant(id("a")), Participant(id("b"))), messages = listOf(SequenceMessage(id("a"), id("b"))), sourceLanguage = SourceLanguage.PLANTUML), "sequence.participant", "sequence.message")
        assertKeys(ClassIR(classes = listOf(ClassNode(id("A"), "A")), relations = listOf(ClassRelation(id("A"), id("B"), ClassRelationKind.Association)), sourceLanguage = SourceLanguage.PLANTUML), "class.node", "class.relation")
        assertKeys(StateIR(states = listOf(StateNode(id("S"), "S")), transitions = listOf(StateTransition(id("S"), id("T"))), sourceLanguage = SourceLanguage.PLANTUML), "state.node", "state.transition")
        assertKeys(GaugeIR(42.0, sourceLanguage = SourceLanguage.MERMAID), "gauge.arc", "gauge.value")
        assertKeys(KanbanIR(listOf(KanbanColumn(id("todo"), RichLabel.of("Todo"), listOf(KanbanCard(id("card"), RichLabel.of("Card"))))), sourceLanguage = SourceLanguage.MERMAID), "kanban.column", "kanban.card")
        assertKeys(QuadrantChartIR(points = listOf(QuadrantPoint(id("p"), RichLabel.of("P"), 0.2, 0.8)), sourceLanguage = SourceLanguage.MERMAID), "quadrant.area", "quadrant.point")
        assertKeys(SankeyIR(nodes = listOf(Node(id("a")), Node(id("b"))), flows = listOf(SankeyFlow(id("a"), id("b"), 1.0)), sourceLanguage = SourceLanguage.MERMAID), "sankey.node", "sankey.flow")
        assertKeys(JourneyIR(listOf(JourneyStage(RichLabel.of("Stage"), listOf(JourneyStep(RichLabel.of("Step"), 5, actors = listOf(RichLabel.of("Actor")))))), sourceLanguage = SourceLanguage.MERMAID), "journey.stage", "journey.step", "journey.actor")
        assertKeys(GitGraphIR(listOf("main"), listOf(GitCommit(id("c1"), "main", parents = listOf(id("c0")))), sourceLanguage = SourceLanguage.MERMAID), "git.branch", "git.commit", "git.edge")
        assertKeys(WireframeIR(WireBox.Plain(RichLabel.of("Root"), listOf(WireBox.Button(RichLabel.of("OK")))), sourceLanguage = SourceLanguage.PLANTUML), "wireframe.root", "button")
    }

    @Test
    fun emits_semantic_entities_for_special_graph_and_activity_families() {
        assertKeys(GraphIR(nodes = listOf(Node(id("a")), Node(id("b"))), edges = listOf(Edge(id("a"), id("b"))), clusters = listOf(Cluster(id("c"), children = listOf(id("a")))), sourceLanguage = SourceLanguage.PLANTUML), "cluster.c", "edge.0.a->b", "node.a")
        assertKeys(ActivityIR(listOf(ActivityBlock.Action(RichLabel.of("Do")), ActivityBlock.IfElse(RichLabel.of("x"), thenBranch = listOf(ActivityBlock.Note(RichLabel.of("note"))))), sourceLanguage = SourceLanguage.PLANTUML), "activity.0", "activity.1", "then")
    }

    @Test
    fun layout_anchors_take_precedence_over_round_robin_semantic_buckets() {
        val model = GraphIR(
            nodes = listOf(Node(id("a")), Node(id("b"))),
            edges = listOf(Edge(id("a"), id("b"))),
            clusters = listOf(Cluster(id("c"), children = listOf(id("a")))),
            sourceLanguage = SourceLanguage.PLANTUML,
        )
        val laidOut = LaidOutDiagram(
            source = model,
            nodePositions = mapOf(
                id("a") to Rect(Point(10f, 10f), Size(30f, 30f)),
                id("b") to Rect(Point(90f, 10f), Size(30f, 30f)),
            ),
            edgeRoutes = listOf(EdgeRoute(id("a"), id("b"), listOf(Point(40f, 25f), Point(90f, 25f)))),
            clusterRects = mapOf(id("c") to Rect(Point(0f, 0f), Size(60f, 60f))),
            bounds = Rect(Point(0f, 0f), Size(140f, 80f)),
        )
        val sink = FrameEntityRenderer.sink(
            prefix = "test",
            model = model,
            laidOut = laidOut,
        )
        sink += DrawCommand.FillRect(Rect(Point(12f, 12f), Size(6f, 6f)), Color.Black)
        sink += DrawCommand.FillRect(Rect(Point(94f, 14f), Size(6f, 6f)), Color.Black)
        sink += DrawCommand.FillRect(Rect(Point(55f, 23f), Size(8f, 4f)), Color.Black)
        val entities = sink.entities()
        val keys = entities.map { it.key }
        assertTrue(keys.any { it == "test.node.a" }, "Expected node a anchor in $keys")
        assertTrue(keys.any { it == "test.node.b" }, "Expected node b anchor in $keys")
        assertTrue(keys.any { it == "test.edge.0.a->b" }, "Expected edge anchor in $keys")
    }

    private fun assertKeys(model: com.hrm.diagram.core.ir.DiagramModel, vararg fragments: String) {
        val sink = FrameEntityRenderer.sink("test", model)
        sampleCommands(80).forEach { sink += it }
        val keys = sink.entities().map { it.key }
        for (fragment in fragments) {
            assertTrue(keys.any { fragment in it }, "Expected entity key containing '$fragment' in $keys")
        }
        assertTrue(keys.size > 1, "Expected semantic split, got $keys")
    }

    private fun sampleCommands(count: Int): List<DrawCommand> =
        List(count) { index ->
            DrawCommand.FillRect(
                rect = Rect(Point(index.toFloat(), 0f), Size(1f, 1f)),
                color = Color.Black,
            )
        }

    private fun id(value: String): NodeId = NodeId(value)
}
