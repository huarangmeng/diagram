# 通用 IR 规范

所有 IR 类型在 `:diagram-core` 包 `com.hrm.diagram.core.ir` 下，全部 `data class` / `sealed`，**不可变**。

## 0. 顶层

```kotlin
sealed interface DiagramModel {
    val title: String?
    val sourceLanguage: SourceLanguage
    val styleHints: StyleHints
}

data class StyleHints(
    val direction: Direction? = null,         // 提示方向
    val theme: String? = null,                // mermaid theme 名 / skinparam
    val extras: Map<String, String> = emptyMap(),
)
```

## 1. 公共原子

```kotlin
@JvmInline value class NodeId(val value: String)

data class Node(
    val id: NodeId,
    val label: RichLabel,
    val shape: NodeShape = NodeShape.Box,
    val style: NodeStyle = NodeStyle.Default,
    val ports: List<Port> = emptyList(),
    val payload: Map<String, String> = emptyMap(),
)

data class Edge(
    val from: NodeId,
    val to: NodeId,
    val label: RichLabel? = null,
    val kind: EdgeKind = EdgeKind.Solid,
    val arrow: ArrowEnds = ArrowEnds.ToOnly,
    val fromPort: PortId? = null,
    val toPort: PortId? = null,
    val style: EdgeStyle = EdgeStyle.Default,
)

data class Cluster(
    val id: NodeId,
    val label: RichLabel?,
    val children: List<NodeId>,
    val nestedClusters: List<Cluster> = emptyList(),
    val style: ClusterStyle = ClusterStyle.Default,
)

sealed interface RichLabel {
    data class Plain(val text: String) : RichLabel
    data class Markdown(val source: String) : RichLabel
    data class Html(val html: String) : RichLabel        // PlantUML/DOT HTML-like
}
```

## 2. IR 家族

> 命名约定：`<家族>IR`，每个家族对应一类布局算法。

| IR | 字段要点 | 覆盖 |
|---|---|---|
| `GraphIR(nodes, edges, clusters)` | 通用图 | flowchart, classDiagram, stateDiagram, erDiagram, requirementDiagram, architectureDiagram, c4, block, PlantUML class/usecase/component/state/object/deployment/erd/network/archimate/c4, DOT 全部 |
| `SequenceIR(participants, lifeline, messages, fragments)` | 时序消息 + frame（loop/alt/par） | Mermaid sequence, PlantUML sequence |
| `TimeSeriesIR(tracks, items, range)` | 时间区间项 | gantt, timeline, timing |
| `TreeIR(root, children)` | 递归子节点 | mindmap, wbs |
| `JourneyIR(stages, steps)` | 阶段 + 步骤 + 评分 | journey |
| `PieIR(slices, total)` / `GaugeIR(value, range)` | 数值分布 | pie, gauge |
| `KanbanIR(columns, cards)` | 列 + 卡片 | kanban |
| `XYChartIR(axes, series)` | 坐标系 + 多系列 | xyChart |
| `SankeyIR(nodes, flows)` | 流量分配 | sankey |
| `GitGraphIR(commits, branches)` | 提交 DAG + 分支 | gitGraph |
| `ActivityIR(start, blocks, end)` | 链式控制流 | PlantUML activity |
| `WireframeIR(rootBox, children)` | 嵌套 UI 盒 | PlantUML wireframe (Salt) |
| `StructIR(root)` | 任意嵌套键值树 | PlantUML json / yaml / ditaa |

每个 IR 的具体 schema 在对应 Phase 实现时落到 `core/ir/<Family>IR.kt` 里，并在本文件用一节补全示例。

## 3. Diagnostics

```kotlin
data class Diagnostic(
    val severity: Severity,
    val message: String,
    val span: Span?,                    // 行/列范围
    val code: String,                   // e.g. "MERMAID-E001"
)
enum class Severity { ERROR, WARNING, INFO }
data class Span(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)
```

诊断码命名：`<LANG>-<E|W|I><三位数>`，全部集中在 `docs/diagnostics.md`（待生成）。

## 4. 不变式
- `Edge.from` 和 `Edge.to` 必须能在同一个 `GraphIR.nodes` 或 `Cluster.children` 中找到。
- `Cluster` 不允许循环嵌套。
- `RichLabel.Markdown` 的渲染不引入外部依赖；只支持子集（粗体/斜体/代码/换行/链接）。
