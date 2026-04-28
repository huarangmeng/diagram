package com.hrm.diagram.core.ir

/* ---------- Graph family ---------- */

data class GraphIR(
    val nodes: List<Node>,
    val edges: List<Edge> = emptyList(),
    val clusters: List<Cluster> = emptyList(),
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Sequence ---------- */

data class Participant(
    val id: NodeId,
    val label: RichLabel = RichLabel.Empty,
    val kind: ParticipantKind = ParticipantKind.Participant,
)
enum class ParticipantKind { Actor, Participant, Boundary, Control, Entity, Database, Collections, Queue }

data class SequenceMessage(
    val from: NodeId,
    val to: NodeId,
    val label: RichLabel = RichLabel.Empty,
    val kind: MessageKind = MessageKind.Sync,
    val activate: Boolean = false,
    val deactivate: Boolean = false,
)
enum class MessageKind { Sync, Async, Reply, Create, Destroy, Note }

/** loop / alt / par / opt / break / critical / group fragments. */
data class SequenceFragment(
    val kind: FragmentKind,
    val title: RichLabel? = null,
    val branches: List<List<SequenceMessage>> = emptyList(),
)
enum class FragmentKind { Loop, Alt, Opt, Par, Break, Critical, Group }

data class SequenceIR(
    val participants: List<Participant>,
    val messages: List<SequenceMessage> = emptyList(),
    val fragments: List<SequenceFragment> = emptyList(),
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- TimeSeries (gantt / timeline / timing) ---------- */

data class TimeRange(val startMs: Long, val endMs: Long) {
    init { require(endMs >= startMs) { "TimeRange end < start" } }
}
data class TimeItem(
    val id: NodeId,
    val label: RichLabel = RichLabel.Empty,
    val range: TimeRange,
    val trackId: NodeId,
    val depends: List<NodeId> = emptyList(),
    val payload: Map<String, String> = emptyMap(),
)
data class TimeTrack(val id: NodeId, val label: RichLabel = RichLabel.Empty)

data class TimeSeriesIR(
    val tracks: List<TimeTrack>,
    val items: List<TimeItem>,
    val range: TimeRange,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Tree (mindmap / wbs) ---------- */

data class TreeNode(
    val id: NodeId,
    val label: RichLabel = RichLabel.Empty,
    val children: List<TreeNode> = emptyList(),
    val style: NodeStyle = NodeStyle.Default,
)

data class TreeIR(
    val root: TreeNode,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Journey ---------- */

data class JourneyStep(val label: RichLabel, val score: Int, val actors: List<RichLabel> = emptyList())
data class JourneyStage(val label: RichLabel, val steps: List<JourneyStep>)

data class JourneyIR(
    val stages: List<JourneyStage>,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Pie / Gauge ---------- */

data class PieSlice(val label: RichLabel, val value: Double)

data class PieIR(
    val slices: List<PieSlice>,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel {
    val total: Double get() = slices.sumOf { it.value }
}

data class GaugeIR(
    val value: Double,
    val min: Double = 0.0,
    val max: Double = 100.0,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Kanban ---------- */

data class KanbanCard(
    val id: NodeId,
    val label: RichLabel,
    val payload: Map<String, String> = emptyMap(),
)
data class KanbanColumn(val id: NodeId, val label: RichLabel, val cards: List<KanbanCard>)

data class KanbanIR(
    val columns: List<KanbanColumn>,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- XYChart ---------- */

enum class AxisKind { Linear, Log, Category, Time }
data class Axis(val title: RichLabel? = null, val kind: AxisKind = AxisKind.Linear, val min: Double? = null, val max: Double? = null, val categories: List<String> = emptyList())
enum class SeriesKind { Line, Bar, Scatter, Area }
data class Series(val name: String, val kind: SeriesKind, val xs: List<Double>, val ys: List<Double>) {
    init { require(xs.size == ys.size) { "Series xs/ys size mismatch" } }
}

data class XYChartIR(
    val xAxis: Axis,
    val yAxis: Axis,
    val series: List<Series>,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- QuadrantChart ---------- */

data class QuadrantPoint(
    val id: NodeId,
    val label: RichLabel,
    val x: Double,
    val y: Double,
    val payload: Map<String, String> = emptyMap(),
)

data class QuadrantChartIR(
    val xMinLabel: RichLabel? = null,
    val xMaxLabel: RichLabel? = null,
    val yMinLabel: RichLabel? = null,
    val yMaxLabel: RichLabel? = null,
    val quadrantLabels: Map<Int, RichLabel> = emptyMap(), // 1..4
    val points: List<QuadrantPoint> = emptyList(),
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Sankey ---------- */

data class SankeyFlow(val from: NodeId, val to: NodeId, val value: Double)

data class SankeyIR(
    val nodes: List<Node>,
    val flows: List<SankeyFlow>,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- GitGraph ---------- */

data class GitCommit(
    val id: NodeId,
    val branch: String,
    val parents: List<NodeId> = emptyList(),
    val tag: String? = null,
    val type: GitCommitType = GitCommitType.Normal,
    val label: RichLabel = RichLabel.Empty,
)
enum class GitCommitType { Normal, Reverse, Highlight, Merge, CherryPick }

data class GitGraphIR(
    val branches: List<String>,
    val commits: List<GitCommit>,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Activity (PlantUML chained control flow) ---------- */

sealed interface ActivityBlock {
    data class Action(val label: RichLabel) : ActivityBlock
    data class IfElse(val cond: RichLabel, val thenBranch: List<ActivityBlock>, val elseBranch: List<ActivityBlock> = emptyList()) : ActivityBlock
    data class While(val cond: RichLabel, val body: List<ActivityBlock>) : ActivityBlock
    data class ForkJoin(val branches: List<List<ActivityBlock>>) : ActivityBlock
    data class Note(val text: RichLabel) : ActivityBlock
}

data class ActivityIR(
    val blocks: List<ActivityBlock>,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Wireframe (PlantUML salt) ---------- */

sealed interface WireBox {
    val label: RichLabel
    data class Plain(override val label: RichLabel, val children: List<WireBox> = emptyList()) : WireBox
    data class Button(override val label: RichLabel) : WireBox
    data class Input(override val label: RichLabel, val placeholder: String? = null) : WireBox
    data class Image(override val label: RichLabel, val src: String? = null) : WireBox
    data class TabbedGroup(override val label: RichLabel, val tabs: List<WireBox>) : WireBox
}

data class WireframeIR(
    val root: WireBox,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Struct (json / yaml / ditaa) ---------- */

sealed interface StructNode {
    val key: String?

    data class Scalar(override val key: String?, val value: String) : StructNode
    data class ObjectNode(override val key: String?, val entries: List<StructNode>) : StructNode
    data class ArrayNode(override val key: String?, val items: List<StructNode>) : StructNode
}

data class StructIR(
    val root: StructNode,
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

/* ---------- Class diagram ---------- */

enum class Visibility { PUBLIC, PRIVATE, PROTECTED, PACKAGE }
enum class Classifier { Static, Abstract }
data class ClassParam(val name: String, val type: String? = null)

data class ClassMember(
    val visibility: Visibility = Visibility.PACKAGE,
    val name: String,
    val type: String? = null,
    val params: List<ClassParam> = emptyList(),
    val isMethod: Boolean = false,
    val isStatic: Boolean = false,
    val isAbstract: Boolean = false,
)

data class ClassNode(
    val id: com.hrm.diagram.core.ir.NodeId,
    val name: String,
    val generics: String? = null,
    val stereotype: String? = null,
    val members: List<ClassMember> = emptyList(),
    val cssClass: String? = null,
)

enum class ClassRelationKind {
    Inheritance, Composition, Aggregation, Association,
    Dependency, Realization, Link, LinkDashed,
}

data class ClassRelation(
    val from: com.hrm.diagram.core.ir.NodeId,
    val to: com.hrm.diagram.core.ir.NodeId,
    val kind: ClassRelationKind,
    val fromCardinality: String? = null,
    val toCardinality: String? = null,
    val label: RichLabel = RichLabel.Empty,
)

data class ClassNamespace(val id: String, val members: List<com.hrm.diagram.core.ir.NodeId>)

enum class NotePlacement { LeftOf, RightOf, TopOf, BottomOf, Standalone }

data class ClassNote(
    val text: RichLabel,
    val targetClass: com.hrm.diagram.core.ir.NodeId? = null,
    val placement: NotePlacement = NotePlacement.RightOf,
)

data class CssClassDef(val name: String, val style: String)

/* ---------- State diagram ---------- */

enum class StateKind { Simple, Initial, Final, Composite, Choice, Fork, Join, History, DeepHistory }

data class StateNode(
    val id: NodeId,
    val name: String,
    val description: String? = null,
    val kind: StateKind = StateKind.Simple,
    val children: List<NodeId> = emptyList(),
)

data class StateTransition(
    val from: NodeId,
    val to: NodeId,
    val event: String? = null,
    val guard: String? = null,
    val action: String? = null,
    val label: RichLabel = RichLabel.Empty,
)

data class StateNote(
    val text: RichLabel,
    val targetState: NodeId? = null,
    val placement: NotePlacement = NotePlacement.RightOf,
)

data class StateIR(
    val states: List<StateNode>,
    val transitions: List<StateTransition>,
    val notes: List<StateNote> = emptyList(),
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel

data class ClassIR(
    val classes: List<ClassNode>,
    val relations: List<ClassRelation> = emptyList(),
    val namespaces: List<ClassNamespace> = emptyList(),
    val notes: List<ClassNote> = emptyList(),
    val cssClasses: List<CssClassDef> = emptyList(),
    override val title: String? = null,
    override val sourceLanguage: SourceLanguage,
    override val styleHints: StyleHints = StyleHints(),
) : DiagramModel
