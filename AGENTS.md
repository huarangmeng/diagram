# AGENTS.md

> 给 AI agent 与新加入开发者的项目导航。**先读这一篇**，再去看 `docs/plan.md`。

---

## 1. 项目一句话

KMP + Compose Multiplatform 的图表渲染框架，**严格兼容** Mermaid / PlantUML / Graphviz DOT 三大语法，**自研解析 + 自研布局**，输出 Compose Canvas 渲染 + SVG / PNG 导出。

不做：可视化拖拽编辑器、双向编辑、协同。

目标平台：Android / iOS / Desktop(JVM) / Web(JS, Wasm)。

---

## 1.5 硬约束（红线，违反即驳回）

> 完整规则与理由见 [`docs/rules.md`](./docs/rules.md)。下面是绝对不能碰的红线，agent / 开发者动手前必看。

### MUST NOT
1. **模块反向依赖**：`:diagram-parser` 不得依赖 `:diagram-layout` 或 `:diagram-render`；`:diagram-layout` 不得依赖 `:diagram-parser` 或 `:diagram-render`；`:diagram-core` 不得依赖任何兄弟模块；`:diagram-render` 是唯一聚合三者的门面。`:diagram-parser` 内部的 `mermaid` / `plantuml` / `dot` 三个子包之间也不得互相 import。
2. **commonMain 引入平台 API**：JVM/Android/iOS/JS 专属 API 一律走 `expect/actual`。
3. **解析器抛异常**：必须返回 `ParseResult(model?, diagnostics)`，遇到任何输入都不允许 throw（除非内部不变式被破坏的 assert）。
4. **引入禁止依赖**：Batik、ELK、dagre.js、Graphviz native、Apache POI、SVG Salamander、任何 JS interop 来"借力"布局/渲染。
5. **在渲染/导出阶段重新测量文本**：所有文本尺寸必须在布局阶段用共享 `TextMeasurer` 测好写入几何字段。
6. **未经 ADR 改动公开 API / IR / DrawCommand**：`docs/api.md`、`docs/ir.md`、`docs/draw-command.md` 是契约文档，破坏性变更必须先在 `docs/adr/` 立 ADR。
7. **跳过黄金语料 / 快照测试**：每个图类型实现必须配 `commonTest` 黄金语料；修 bug 必须先加复现样例。
8. **改"关键决策"表**（§2）：决策由用户拍板，agent 不得自行推翻。
9. **破坏增量契约**：任何新 lexer / parser / IR / layout / render 必须满足 `docs/streaming.md` 规约（resumable lexer + 行/块增量 parser + IrPatch + pinned layout + 增量 DrawCommand）；不允许实现"只能一次性吃全文"的算法。性能预算见 `docs/rules.md §F2`。

### MUST
1. 接到任务先读本文件 → `docs/plan.md` 找到对应 Phase / todo → 检查 SQL `todos` / `todo_deps` → 设 `in_progress` → 实现 → 跑测 → 设 `done`。
2. 公开类型加 `@DiagramApi` + KDoc + 最小用法示例。
3. 新增图类型 / 语法 / 布局算法严格按 [`docs/contributing.md`](./docs/contributing.md) 步骤走，并更新对应 `docs/syntax-compat/*.md`。
4. 新增诊断码同步更新 [`docs/diagnostics.md`](./docs/diagnostics.md)。
5. 任意 Phase 状态变化更新本文 §8 与 `docs/plan.md`。

---

## 2. 关键决策（不要再推翻，除非用户明说）

| 主题 | 决策 |
|---|---|
| 形态 | 纯解析 + 渲染 SDK，不做可视化编辑器 |
| 一等公民用例 | **LLM 流式增量**：append-only 文本流，整链路 resumable / incremental（详见 `docs/streaming.md`） |
| 交付节奏 | 全量规划，分 8 个 Phase 推进（见 `docs/plan.md` §9） |
| 布局算法 | **完全自研**，纯 KMP，不依赖 ELK/dagre 等 JS/Java 库 |
| 渲染输出 | Compose Canvas + 导出 PNG + 导出 SVG（全套） |
| 语法兼容度 | **严格兼容**，能直接跑官方 .mmd / .puml / .dot 文本 |

---

## 3. 文档索引

入口先读：[`docs/README.md`](./docs/README.md)。下面是高频用到的：

| 类别 | 文档 | 用途 |
|---|---|---|
| 路线 | [`docs/plan.md`](./docs/plan.md) | 8 Phase 路线图、模块切分、布局算法清单、风险表 |
| 架构 | [`docs/architecture.md`](./docs/architecture.md) | 顶层数据流、模块边界、扩展点、不变式、性能目标 |
| API | [`docs/api.md`](./docs/api.md) | 公开 API 契约（`Diagram.parse` / `DiagramView` / 导出） |
| IR | [`docs/ir.md`](./docs/ir.md) | 通用 IR（`DiagramModel` 家族）字段语义 |
| 渲染 | [`docs/draw-command.md`](./docs/draw-command.md) | Compose / SVG / PNG 共用的渲染指令集 |
| 渲染 | [`docs/theme.md`](./docs/theme.md) | 主题、颜色、字体、形状、箭头 |
| 渲染 | [`docs/coordinate-system.md`](./docs/coordinate-system.md) | 坐标 / 单位 / DPI / 文本基线 |
| 布局 | [`docs/layout/README.md`](./docs/layout/README.md) | 布局接口与算法目录 |
| 兼容 | [`docs/syntax-compat/mermaid.md`](./docs/syntax-compat/mermaid.md) | Mermaid 兼容矩阵 |
| 兼容 | [`docs/syntax-compat/plantuml.md`](./docs/syntax-compat/plantuml.md) | PlantUML 兼容矩阵 |
| 兼容 | [`docs/syntax-compat/dot.md`](./docs/syntax-compat/dot.md) | Graphviz DOT 兼容矩阵 |
| 工程 | [`docs/rules.md`](./docs/rules.md) | **硬规则手册**（红线 / 严重等级 / 自动化钩子） |
| 工程 | [`docs/testing.md`](./docs/testing.md) | 黄金语料、快照、跨平台测试 |
| 工程 | [`docs/contributing.md`](./docs/contributing.md) | 新增图类型 / 语法 / 布局算法的步骤 |
| 工程 | [`docs/diagnostics.md`](./docs/diagnostics.md) | 诊断码命名与已分配清单 |
| 工程 | [`docs/release.md`](./docs/release.md) | 版本号、Maven 工件、发布流程 |
| 决策 | [`docs/adr/`](./docs/adr/) | 架构决策记录（ADR） |
| 模板 | [`README.md`](./README.md) | KMP 模板的原始构建说明（保留） |

---

## 4. 仓库结构（演进中）

当前仓库已经完成核心模块拆分，现状如下：

```
:diagram-core      // 通用 IR、几何、Theme、DrawCommand、SVG 导出（commonMain）+ PNG/JPEG（expect/actual）
:diagram-layout    // 自研布局算法集合
:diagram-parser    // 三家语法 lexer/parser/lowering，子包隔离：parser.{mermaid,plantuml,dot}
:diagram-render    // Compose Canvas 渲染、交互 + 顶层门面 Diagram.session / rememberDiagramSession / DiagramCanvas
:diagram-bench     // 基准与性能实验（预留）
:composeApp        // Demo gallery
:androidApp        // Android 宿主壳
:iosApp            // iOS 宿主壳（Xcode）
```

> 仍处于演进期：模块骨架已建，但 Phase 1 之后的能力尚未全部补齐，仍以 `docs/plan.md` 为交付路线。
> 当前 streaming 渲染已通过 `DrawCommandStore` 收敛 patch 语义：`DiagramSnapshot.drawCommands` 保持完整帧，`SessionPatch.addedDrawCommands` 只表达本轮新增命令；DOT session 使用 statement-level 增量 parser，禁止回退到 append 时全文 `source.toString()` 重解析。

---

## 5. 命令速查

> 当前仓库已具备多模块构建与测试任务；仓库级 `./gradlew allTests` 已可作为基线回归命令。

```bash
# 构建
./gradlew :androidApp:assembleDebug              # Android（独立模块 :androidApp）
./gradlew :composeApp:run                        # Desktop (JVM)
./gradlew :composeApp:wasmJsBrowserDevelopmentRun  # Web (Wasm)

# 测试（commonTest 跨平台跑）
./gradlew allTests
./gradlew :composeApp:jvmTest
# 定向测试用具体平台任务；聚合 allTests 不支持 --tests
./gradlew :diagram-parser:jvmTest :diagram-render:jvmTest --tests '*Dot*'

# iOS：用 Xcode 打开 iosApp/
```

---

## 6. 代码规范

1. **包名根**：`com.hrm.diagram`，子包按模块切：`com.hrm.diagram.core`、`...layout`、`...mermaid` 等。
2. **commonMain 优先**：除非真正需要平台 API，否则一切代码进 `commonMain`。
3. **expect/actual**：仅用于平台栅格化（PNG）、字体度量补充、文件 IO，禁止泛滥。
4. **不引入大依赖**：解析器/布局/渲染只允许依赖 Compose、kotlinx.coroutines、kotlinx.serialization（如需）。**禁止** Batik、ELK Java、JS 互操作绕过自研约定。
5. **数据类先行**：IR 全部 `data class` / `sealed`，不可变。状态变化在渲染层通过新 IR 实例驱动。
6. **错误处理**：解析器返回 `ParseResult`（带 diagnostics 列表），不要抛异常；渲染层对未知节点降级为占位框。
7. **测试**：每个语法子模块必须有 `commonTest` 黄金语料快照；每个布局算法必须有确定性种子的坐标快照。
8. **公开 API 注释**：所有 `public` 类/函数加 KDoc，给出最小用法示例。
9. **命名**：`*Parser`、`*Lexer`、`*Layout`、`*Renderer`、`*IR`；避免 `Util/Helper/Manager` 这类空泛后缀。
10. **图表排版分层**：Graph 类图型应先布局 primary graph，再处理 port、anchored note、edge label 等 decoration；decoration 不应直接参与 Sugiyama 主图排层，避免 package/component/note/edge label 互相挤占业务节点空间。

---

## 7. Agent 工作流

接到任务时按这个顺序推进：

1. 读 `AGENTS.md`（本文）确认全局约束。
2. 读 `docs/plan.md` 找到任务对应的 Phase / todo。
3. 检查 SQL 中 `todos` 表对应 todo 的 `status` 与 `todo_deps`，确认前置已 `done`。
4. 把要动手的 todo 设为 `in_progress`。
5. 实现 → 写测试 → 跑 `./gradlew allTests`。
6. 完成后把 todo 设为 `done`，必要时新增 `docs/` 下的设计文档并更新本索引。
7. 不要修改"关键决策"表里的内容；要改先和用户确认。

---
