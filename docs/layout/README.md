# 布局引擎

## 1. 接口

```kotlin
interface Layout<in I : DiagramModel, out O : LaidOutDiagram> {
    val id: String
    fun layout(model: I, options: LayoutOptions): O
}

object LayoutRegistry {
    fun register(layout: Layout<*, *>)
    fun forModel(model: DiagramModel, options: LayoutOptions): Layout<*, *>
}
```

`LaidOutDiagram` 包含原 IR + 几何（每个节点的 `Rect`，每条边的 `PathCmd`，每个标签的 `Rect`）+ `bounds`。

## 2. 算法目录（按 Phase 落地，每实现一个补一篇专题文档）

| 算法 ID | 目标 IR | 复杂度 | 文档 |
|---|---|---|---|
| `sugiyama` | `GraphIR`（DAG） | O(V·E) | `sugiyama.md` (Phase 1) |
| `force-directed` | `GraphIR`（无向） | O(V²) / Barnes-Hut O(V log V) | `force-directed.md` (Phase 4) |
| `orthogonal-astar` | `GraphIR`（块/正交） | O(V² log V) | `orthogonal-astar.md` (Phase 3) |
| `tree-rt` | `TreeIR` | O(V) | `tree-rt.md` (Phase 2) |
| `radial` | `TreeIR` | O(V) | `radial.md` (Phase 2 可选) |
| `timeline` | `TimeSeriesIR` / `SequenceIR` | O(V + E) | `timeline.md` (Phase 1/2) |
| `sankey` | `SankeyIR` | O(V·E) | `sankey.md` (Phase 2) |
| `grid-pack` | `KanbanIR` / `PieIR` / `GaugeIR` / `XYChartIR` | O(V) | `grid-pack.md` (Phase 2) |
| `nested` | `StructIR` / `WireframeIR` / 嵌套 cluster | O(V) | `nested.md` (Phase 5) |

## 3. 公共子能力

- `EdgeRouter`：`straight / polyline / orthogonal / cubic-bezier`
- `LabelPlacer`：贪心避让；冲突最少的位置（侧/上/下）
- `ClusterPacker`：子图矩形包络与嵌套
- `RandomSource`：种子可控的伪随机（用于力导向，确保确定性）

## 4. 算法专题文档约定
每个算法专题文档包含：
1. 目标问题
2. 算法步骤（每步给伪代码）
3. 复杂度
4. 关键参数（暴露给 `LayoutOptions.hints`）
5. 已知缺陷 / 退化输入
6. 黄金测试样例链接
