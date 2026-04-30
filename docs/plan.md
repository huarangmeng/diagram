# KMP Diagram —— 多语法图表渲染框架 实施计划

## 1. 项目目标

构建一个 Kotlin Multiplatform + Compose Multiplatform 的图表渲染框架，
**严格兼容** Mermaid（`.mmd`）、PlantUML（`.puml`）、Graphviz DOT（`.dot`）三大语法；
**自研解析 + 自研布局算法**，使用 Compose Canvas 渲染，并支持导出 PNG / SVG。
目标平台：Android / iOS / Desktop(JVM) / Web(JS, Wasm)。

不在范围：可视化拖拽编辑器、双向编辑、协同。

### 1.1 一等公民用例：LLM 流式增量

> **关键横向需求**：本框架的核心使用场景是接收 LLM 流式输出的 Mermaid / PlantUML / DOT 文本，
> 边接收边解析、布局、渲染。所有 Phase 的实现都必须满足 `docs/streaming.md` 中规约的：
>
> - **Append-only 流式 API**：`Diagram.session()` + `session.append(chunk)` + `session.finish()`。
> - **Resumable Lexer**：保留 `LexerState`，从上次 safePoint 续跑。
> - **增量 Parser**：行/块边界推进，partial AST 永远可读。
> - **Append-only IR**：稳定 `NodeId`，patch 流而非全量替换。
> - **Pinned + Append Layout**：已布局节点坐标钉住，新增节点局部追加。
> - **DrawCommand 增量 + 视口剔除 + 测量缓存**：60fps 万节点。
> - **性能预算**：每次 append < 16 ms；finish < 50 ms；内存 O(源长度)。
>
> 详见 `docs/streaming.md`。Phase 1 起每个语法/布局/渲染 PR 都要给出 streaming 切片测试。

---

## 2. 顶层架构

采用经典编译器三段式 + 渲染管线：

```
源文本 ──► [Lexer] ──► Tokens ──► [Parser] ──► 通用 IR (DiagramModel)
                                                    │
                                                    ▼
                                       [Layout Engine] (按图类型选择算法)
                                                    │
                                                    ▼
                                          LaidOutDiagram (含坐标)
                                                    │
                       ┌────────────────────────────┼───────────────────────────┐
                       ▼                            ▼                           ▼
                Compose Canvas Renderer     SVG Exporter (commonMain)   PNG Exporter (expect/actual)
```

### 模块（Gradle 子模块）划分

```
:diagram-core      // 通用 IR、几何、颜色、字体、主题、Style token、DrawCommand、SVG 导出（commonMain）+ PNG/JPEG（expect/actual）
:diagram-layout    // 布局算法集合（分层、力导向、正交、树、放射、环形等）
:diagram-parser    // 三家语法 lexer/parser/lowering，按子包隔离：parser.mermaid / parser.plantuml / parser.dot
:diagram-render   // Compose Canvas 渲染、交互（缩放/拖拽/选中）+ 顶层门面 API（Diagram.parse / DiagramView）
:composeApp        // Demo & 样例 gallery
```

依赖方向：`compose → parser → core`，`compose → layout → core`。
- `:diagram-core` 不依赖任何模块，纯 KMP（无 Compose）。
- `:diagram-layout` 仅依赖 `:diagram-core`；不知道任何语法。
- `:diagram-parser` 仅依赖 `:diagram-core`；按子包隔离三家语法，互不可见。
- `:diagram-render` 是唯一面向应用层的门面，把上述三者粘合并提供 `DiagramView`。

### 顶层公开 API（草案）

```kotlin
// 解析
val model: DiagramModel = Diagram.parse(source)               // 自动识别语法
val model = MermaidParser.parse(text)                         // 显式

// 布局
val laidOut = model.layout(LayoutOptions(theme = Theme.Light))

// Compose 渲染
@Composable
fun App() {
    DiagramView(
        source = mermaidText,
        modifier = Modifier.fillMaxSize(),
        interaction = DiagramInteraction(zoom = true, pan = true),
        theme = DiagramTheme.Default,
    )
}

// 导出
laidOut.toSvg(): String
laidOut.toPng(width = 1920, scale = 2f): ByteArray
```

---

## 3. 通用 IR 设计（`:diagram-core`）

把三家语法的差异在解析阶段消化掉，下游统一处理。
顶层类型按 "图族" 分组，每族一种 IR：

| IR 类型 | 覆盖来源 |
|---|---|
| `GraphIR`（节点+有/无向边+子图） | Mermaid flowchart, classDiagram, stateDiagram, erDiagram, requirementDiagram, architectureDiagram, c4, block; PlantUML class/usecase/component/state/object/deployment/erd/network/archimate/c4; DOT digraph/graph/cluster |
| `SequenceIR`（参与者+消息时间轴） | Mermaid sequenceDiagram; PlantUML sequence |
| `TimeSeriesIR`（任务+时间区间） | Mermaid gantt, timeline; PlantUML gantt, timing |
| `TreeIR`（根+多层子节点） | Mermaid mindmap; PlantUML mindmap, wbs |
| `JourneyIR`（阶段+步骤+得分） | Mermaid journey |
| `PieIR` / `GaugeIR` | Mermaid pie, gauge |
| `KanbanIR` | Mermaid kanban |
| `XYChartIR` | Mermaid xyChart |
| `SankeyIR` | Mermaid sankey |
| `GitGraphIR` | Mermaid gitGraph |
| `ActivityIR` | PlantUML activity（特殊：链式控制流） |
| `WireframeIR` | PlantUML wireframe（盒模型） |
| `StructIR` | PlantUML json / yaml / ditaa |

每个 IR 都实现 `DiagramModel`：

```kotlin
sealed interface DiagramModel {
    val title: String?
    val sourceLanguage: SourceLanguage   // MERMAID / PLANTUML / DOT
    val styleHints: StyleHints           // 主题、方向、字号等
}
```

公共原子：`NodeId`、`Node`、`Edge(from,to,style,label)`、`Cluster`、
`PortAnchor`（端口对接位置）、`LabelBox`、`Geom(point/rect/path)`。

---

## 4. 解析器策略

每个语法采用相同骨架：

1. **Lexer**：基于位置的字符流 + 状态机，输出 `Token(kind, lexeme, span)`。
   - Mermaid 是行驱动；PlantUML 是行驱动 + `@startxxx/@endxxx` 块；DOT 是 C 风格 token。
2. **Parser**：递归下降；每种图类型一个 sub-parser。
3. **Diagnostics**：保留 span，错误以 `ParseError(line, col, msg)` 累积返回。
4. **AST → IR Lowering**：把语法树折叠成上面定义的 `DiagramModel`。

为保证 "严格兼容"，每个子解析器配一份 **黄金语料库**：
从官方仓库 / 文档站收集 50-200 条样本，作为 parser 回归测试输入。

---

## 5. 布局算法集（`:diagram-layout`）

每个算法以 `Layout` 接口暴露：

```kotlin
interface Layout<I : DiagramModel, O : LaidOutDiagram> {
    fun layout(model: I, options: LayoutOptions): O
}
```

规划要实现的算法（按需要它们的图类型反推）：

| 算法 | 服务的图 |
|---|---|
| **Sugiyama 分层布局**（DAG → 层 → 交叉最小化 → 坐标分配） | flowchart, classDiagram, stateDiagram, erDiagram, activity, component, deployment, requirementDiagram, architectureDiagram, c4, DOT digraph, gitGraph |
| **力导向 (Fruchterman-Reingold / Barnes-Hut)** | usecase, network, object, archimate, 任意 `graph` 无向 |
| **正交布局 + 直角折线路由** | block, wireframe, ditaa |
| **树式布局 (Reingold-Tilford)** | mindmap, wbs, tree |
| **放射 / 环形布局** | mindmap 备选, journey 环形模式 |
| **时间轴线性布局** | gantt, timing, timeline, sequenceDiagram(纵向时间) |
| **径向桑基** | sankey |
| **网格 + 容量装箱** | kanban, pie, gauge, xyChart 笛卡尔坐标 |
| **结构嵌套布局** | json, yaml, c4 容器嵌套 |

公共子模块：

- 边路由：直线 / 折线（曼哈顿） / 三次贝塞尔 / 正交 A*（块布局用）。
- 标签避让：贪心 + 简化的 force-based label placement。
- 集群（subgraph）矩形包络 & 嵌套布局。

---

## 6. Compose 渲染层（`:diagram-render`）

核心 Composable：

```kotlin
@Composable
fun DiagramView(
    model: LaidOutDiagram,
    modifier: Modifier = Modifier,
    theme: DiagramTheme = DiagramTheme.Default,
    interaction: DiagramInteraction = DiagramInteraction(),
    onNodeClick: ((NodeId) -> Unit)? = null,
)
```

实现要点：
- 用单一 `Canvas` + `drawScope`；所有图元抽象为 `DrawCommand`（`DrawRect`, `DrawPath`, `DrawText`, `DrawArrow`, `DrawIcon`）。
- 文本测量统一通过 `rememberTextMeasurer()`，并在 **布局阶段** 就用同一 measurer 测过尺寸（避免布局/渲染字号不一致）。
- 交互：`Modifier.pointerInput` 实现 pan/zoom；命中检测基于 IR + 几何空间索引（quadtree）。
- 动画：节点淡入、布局过渡用 `Animatable`。
- 主题：`DiagramTheme(color, font, stroke, arrowHead, nodeShapeOverride)`，并提供 Mermaid 默认主题、暗色、Material You 三套。

---

## 7. 导出层（合并入 `:diagram-core`）

SVG 与位图共用同一份 "已布局图 → DrawCommand 流" 中间层，统一收在 `:diagram-core`（SVG 在 commonMain；PNG/JPEG 用 expect/actual）。

### SVG（纯 commonMain）
- `LaidOutDiagram.toSvg(): String` 遍历 `DrawCommand` 写 XML 字符串。
- 字体：嵌入字体名 + fallback；可选嵌入 base64 字体。

### PNG / JPEG（expect/actual）
- `expect fun LaidOutDiagram.toPng(width: Int, scale: Float = 1f): ByteArray`
- `expect fun LaidOutDiagram.toJpeg(width: Int, quality: Int = 90, scale: Float = 1f): ByteArray`
- JVM：`java.awt.image.BufferedImage` + 自绘 `DrawCommand`（不依赖 Batik）。
- Android：`android.graphics.Canvas` + `Bitmap.compress`。
- iOS：`CoreGraphics` (`CGContext`) + `UIImage` / `UIImageJPEGRepresentation`。
- JS / Wasm：`OffscreenCanvas` + `toBlob`。

每个平台只写一个 `PlatformCanvas` 适配器消费 `DrawCommand`，SVG 与位图共享渲染逻辑。

---

## 8. 测试策略

- **解析器**：黄金语料 + AST 快照测试（`commonTest`，跨平台跑）。
- **布局**：确定性种子 + 坐标快照（容差比较）。
- **渲染**：截图回归测试，JVM 用 Roborazzi/自研像素 diff；其它平台靠 SVG 快照。
- **集成**：`source → svg` 端到端字符串快照，最易维护。
- **跨平台**：commonTest 跑解析/布局/SVG；androidUnitTest + jvmTest + iosTest 跑导出。

---

## 9. 分阶段交付

> 当前仓库进度已经超出“只有 Phase 0”的状态。下面除规划外，同时同步实际落地情况。

### Phase 0 — 地基 ✅ 已完成
建立模块结构、`:diagram-core` IR、`DrawCommand`、SVG 导出骨架、demo gallery 框架。

已落地：
- `:diagram-core` 的通用 IR / DrawCommand / Theme / LayoutOptions / SVG 导出骨架；
- `:diagram-render` 的 `Diagram.session(...)`、Compose 侧 `rememberDiagramSession(...)`、`DiagramCanvas`；
- `composeApp` 的多语法 demo gallery 骨架。

### Phase 1 — Mermaid 主力图（Sugiyama 体系） ✅ 已完成
flowchart → sequenceDiagram → classDiagram → stateDiagram → erDiagram。
需要：行驱动 lexer、Sugiyama 布局、时间轴布局、贝塞尔/折线路由、Compose 渲染基础交互。
**里程碑：能渲染 mermaid 官方文档一半以上的示例。**

当前已落地：
- ✅ Mermaid streaming 主链路：`MermaidSessionPipeline` + 5 图型 sub-pipeline（flowchart / sequence / class / state-v2 / erDiagram），均进入 `commonTest`。
- ✅ Mermaid 样式：`classDef` / `class` / `style` / `linkStyle` / `:::` 在 GraphIR 图型中可解析并真实影响 DrawCommand（详见 `docs/style-mermaid.md`）。
- ✅ 验收：已接入 Mermaid 官方样例集，并做 one-shot vs chunked + DrawCommand 签名一致性校验（详见 `diagram-render/src/commonTest/.../MermaidOfficialSampleTest.kt`）。
- ✅ `erDiagram`：最终态（`finish()`）渲染为“实体框内嵌属性列表”；增量态内部仍使用“实体节点 + 属性节点”以满足 append-only IR 与 pinned layout，最终渲染阶段折叠隐藏属性节点与属性连线（详见 `docs/syntax-compat/mermaid.md`）。
- ✅ Mermaid 颜色：支持 hex、CSS 颜色关键字、`rgb/rgba`、`hsl/hsla`；无法识别的颜色会被忽略并记录 `MERMAID-W011`（详见 `docs/diagnostics.md`）。
- ✅ 增量约束：影响几何的样式（如字体/字号/padding）统一延迟到 `finish()` 收敛，避免破坏 pinned layout 契约（详见 `docs/streaming.md`）。
- ⏸️ 本轮暂不计划：PlantUML / DOT 仍为 stub pipeline（保留 Phase 4/6 路线，但当前不推进实现）。

### Phase 2 — Mermaid 数据/时间/树类
gantt、timeline、pie、gauge、journey、mindmap、xyChart、sankey、kanban、gitGraph。
需要：时间轴 / 树 / 桑基 / 网格等布局，xyChart 坐标系。

当前状态：✅ 已完成；`gantt`、`timeline`、`pie`、`gauge`、`journey`、`mindmap`、`xyChart`、`sankey`、`kanban`、`gitGraph` 均已落地 Mermaid streaming 主链路、`commonTest` 与 one-shot vs chunked 一致性校验，并额外完成了 `quadrantChart`。`composeApp` 中对应样例已可作为 Phase 2 验收集使用。

### Phase 3 — Mermaid 进阶结构图 ✅ 已完成
requirementDiagram、architectureDiagram、c4、block。
正交布局 + A* 直角路由，集群嵌套增强。

当前状态：✅ 已完成；`requirementDiagram` 已支持 requirement / element / relation / direction、`style` / `classDef` / `class` / `:::` 样式链路，以及 requirement 文本中的基础 markdown 保真渲染。`architectureDiagram` 已覆盖 `architecture-beta` 官方主语法：group / nested group / service / junction / port-side edge / `{group}` boundary edge / icon，并兼容内置 icon 与 iconify 名称透传。`c4` 已补齐 `C4Context/C4Container/C4Component/C4Dynamic/C4Deployment`、常用元素/边界、`Rel/BiRel/RelIndex/Rel_*`、`AddElementTag/AddRelTag`、`UpdateElementStyle/UpdateRelStyle`、`UpdateLayoutConfig`、`$tags` / `$link` / legend，以及 `RoundedBoxShape` / `EightSidedShape` / `DashedLine` / `DottedLine` / `BoldLine` helper。`block` 已补齐 `block` / `block-beta`、显式 `columns`、`space[:n]`、列跨度、nested `block ... end`、常用形状、block arrow、`-->` / `---` 与带标签连线，并全部接入 Mermaid streaming 主链路、`commonTest` 与 one-shot vs chunked 一致性校验。

### Phase 4 — PlantUML 主体
sequence、usecase、class、activity、component、state、object、deployment、erd。
重点：`@startuml/@enduml` 块、skinparam 主题、PlantUML 特殊连线语法、activity 链式。

当前状态：🟡 已启动；`sequence`、`class`、`state`、`component`、`usecase`、`activity`、`object`、`deployment` 与 `erd` 九条最小可用 streaming 子链路已落地，`Diagram.session(SourceLanguage.PLANTUML)` 不再走 `StubSessionPipeline`。其中 `sequence` 已支持 `@startuml/@enduml`、participant/actor/control/boundary/database/collections/queue、`->`/`<-`/`->>`/`<<-`/`-->`/`<--`/`-->>`/`<<--`、note、activate/deactivate、`return`、`autonumber`、`group/alt/opt/loop/par/critical/break`；`class` 已支持 class/interface/abstract class/enum、成员块、dotted member、`<|--`/`<|..`/`*--`/`o--`/`-->`/`..>`/`--`/`..` 关系、泛型 `<T>` 与定向 note；`state` 已支持 state/state alias/state description、composite/nested composite、`[*]`/`[H]`/`[H*]`、`<<choice>>`/`<<fork>>`/`<<join>>`/`<<history>>`/`<<deep_history>>`、note、`-->`、方向声明与并行分区分隔线 `--` 的最小解析兼容；`component` 已支持 component / `[Component] as X` / interface / port / portin / portout、`-->` / `<--` / `..>` / `<..` / `--` / `..` 关系，以及 package / cloud / node 嵌套；`usecase` 已支持 actor / `:Actor:`、`(usecase)` / `usecase X` / `usecase "Label" as X`、`-->` / `<--` / `..>` / `<..` / `--` / `..` / `<|--` 关系，以及 package / rectangle 嵌套；`activity` 已支持 `start` / `stop`、`:action;`、`if/else/endif`、`while/endwhile` 与 `note`；`object` 已支持 object / `object "Label" as X`、属性块 `{ ... }`、`Obj : key = value`，以及 `-->` / `<--` / `..>` / `<..` / `--` / `..` / `<|--` / `*--` / `o--` 关系；`deployment` 已支持 node / artifact / database / cloud / frame / package、`-->` / `<--` / `..>` / `<..` / `--` / `..` 关系与 cluster 嵌套；`erd` 已支持 `entity`、属性块 `{ ... }`、`*`/`+`/`#` 属性标记、`<<PK/FK/UK>>`、crowfoot 关系如 `||--o{`，并均接入 `commonTest` 与 one-shot vs chunked 一致性校验。`skinparam` 暂记录 `PLANTUML-W001` 后忽略，其余 PlantUML 图型仍待后续切片。

### Phase 5 — PlantUML 扩展
timing、wireframe、archimate、c4、gantt、mindmap、wbs、network、ditaa、json、yaml。
许多复用 Phase 1-3 的布局算法 + 新的 lowering 规则。

当前状态：⬜ 未开始实现。

### Phase 6 — Graphviz DOT
digraph / graph / cluster / 属性子集（rank、shape、style、color、label、port、HTML-like label）。
需要更完整的 Sugiyama（rankdir、constraint、rank=same）。

当前状态：⬜ 未开始实现；顶层 API 已能创建 `SourceLanguage.DOT` session，但默认仍走 `StubSessionPipeline`。

### Phase 7 — 导出与发布
`toSvg()` 全图类型覆盖、`toPng()` 多平台落地、Maven Central 发布、文档站。

当前状态：⬜ 未开始实现；SVG 目前仍以骨架能力为主，PNG/JPEG expect/actual 尚未进入交付态。

---

## 10. 关键风险与对策

| 风险 | 对策 |
|---|---|
| 自研布局难以达到原生质量 | 每个 Phase 选 1 个主算法吃透；先质量再图类型；保留 `LayoutHint` 让用户微调 |
| 严格兼容 → 边角语法暴量 | 黄金语料 + Diagnostics 累积式报错，未支持的语法精确报告位置而不是崩溃 |
| 文本测量在四个平台不一致 | 全部走 Compose `TextMeasurer`，导出层用同样字号 + 度量缓存 |
| Wasm/JS 包体积 | 解析器/布局保持纯 Kotlin、零反射；按图类型代码切分（用接口 + ServiceLoader 风格注册） |
| HTML-like label / Markdown 标签 | 单独做一个 `RichLabel` 子模块，所有渲染器复用 |

---

## 11. 后续可选扩展（不在当前范围）

- 增量布局 / 动画过渡（编辑后局部重排）
- 主题市场 / DSL 扩展点
- 服务端渲染（JVM headless → SVG/PNG 服务）
- LSP / IDE 插件辅助
