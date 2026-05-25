package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.render.streaming.dispatcher.SubPipelineRegistry

internal enum class MermaidDiagramKind {
    Flowchart,
    Sequence,
    Class,
    State,
    Er,
    Pie,
    Gauge,
    Timeline,
    Gantt,
    Mindmap,
    Kanban,
    XYChart,
    Quadrant,
    Journey,
    Sankey,
    GitGraph,
    Requirement,
    Architecture,
    C4,
    Block,
    Packet,
}

internal class MermaidSubPipelineRegistry(
    private val textMeasurer: TextMeasurer,
) : SubPipelineRegistry<MermaidDiagramKind, MermaidSubPipeline> {
    override val defaultKind: MermaidDiagramKind = MermaidDiagramKind.Flowchart

    override fun create(kind: MermaidDiagramKind): MermaidSubPipeline =
        when (kind) {
            MermaidDiagramKind.Flowchart -> MermaidFlowchartSubPipeline(textMeasurer)
            MermaidDiagramKind.Sequence -> MermaidSequenceSubPipeline(textMeasurer)
            MermaidDiagramKind.Class -> MermaidClassSubPipeline(textMeasurer)
            MermaidDiagramKind.State -> MermaidStateSubPipeline(textMeasurer)
            MermaidDiagramKind.Er -> MermaidErSubPipeline(textMeasurer)
            MermaidDiagramKind.Pie -> MermaidPieSubPipeline(textMeasurer)
            MermaidDiagramKind.Gauge -> MermaidGaugeSubPipeline()
            MermaidDiagramKind.Timeline -> MermaidTimelineSubPipeline(textMeasurer)
            MermaidDiagramKind.Gantt -> MermaidGanttSubPipeline(textMeasurer)
            MermaidDiagramKind.Mindmap -> MermaidMindmapSubPipeline(textMeasurer)
            MermaidDiagramKind.Kanban -> MermaidKanbanSubPipeline(textMeasurer)
            MermaidDiagramKind.XYChart -> MermaidXYChartSubPipeline(textMeasurer)
            MermaidDiagramKind.Quadrant -> MermaidQuadrantChartSubPipeline()
            MermaidDiagramKind.Journey -> MermaidJourneySubPipeline(textMeasurer)
            MermaidDiagramKind.Sankey -> MermaidSankeySubPipeline(textMeasurer)
            MermaidDiagramKind.GitGraph -> MermaidGitGraphSubPipeline(textMeasurer)
            MermaidDiagramKind.Requirement -> MermaidRequirementSubPipeline(textMeasurer)
            MermaidDiagramKind.Architecture -> MermaidArchitectureSubPipeline(textMeasurer)
            MermaidDiagramKind.C4 -> MermaidC4SubPipeline(textMeasurer)
            MermaidDiagramKind.Block -> MermaidBlockSubPipeline(textMeasurer)
            MermaidDiagramKind.Packet -> MermaidPacketSubPipeline(textMeasurer)
        }

    fun kindForHeaderText(trimmed: String): MermaidDiagramKind? =
        when {
            trimmed.startsWith("flowchart") || trimmed.startsWith("graph") -> MermaidDiagramKind.Flowchart
            trimmed.startsWith("sequenceDiagram") -> MermaidDiagramKind.Sequence
            trimmed.startsWith("classDiagram") -> MermaidDiagramKind.Class
            trimmed.startsWith("stateDiagram") -> MermaidDiagramKind.State
            trimmed.startsWith("erDiagram") -> MermaidDiagramKind.Er
            trimmed.startsWith("pie") -> MermaidDiagramKind.Pie
            trimmed.startsWith("gauge") -> MermaidDiagramKind.Gauge
            trimmed.startsWith("timeline") -> MermaidDiagramKind.Timeline
            trimmed.startsWith("gantt") -> MermaidDiagramKind.Gantt
            trimmed.startsWith("mindmap") -> MermaidDiagramKind.Mindmap
            trimmed.startsWith("kanban") -> MermaidDiagramKind.Kanban
            trimmed.startsWith("xychart") -> MermaidDiagramKind.XYChart
            trimmed.startsWith("quadrantChart") -> MermaidDiagramKind.Quadrant
            trimmed.startsWith("journey") -> MermaidDiagramKind.Journey
            trimmed.startsWith("sankey") -> MermaidDiagramKind.Sankey
            trimmed.startsWith("gitGraph") -> MermaidDiagramKind.GitGraph
            trimmed.startsWith("requirementDiagram") -> MermaidDiagramKind.Requirement
            trimmed.startsWith("architecture-beta") -> MermaidDiagramKind.Architecture
            trimmed.startsWith("C4Context") ||
                trimmed.startsWith("C4Container") ||
                trimmed.startsWith("C4Component") ||
                trimmed.startsWith("C4Dynamic") ||
                trimmed.startsWith("C4Deployment") -> MermaidDiagramKind.C4
            trimmed == "block" || trimmed.startsWith("block:") || trimmed.startsWith("block-beta") -> MermaidDiagramKind.Block
            trimmed.startsWith("packet-beta") -> MermaidDiagramKind.Packet
            else -> null
        }
}
