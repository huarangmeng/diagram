# 项目规则手册

本文件是项目所有"硬规则"的权威来源。`AGENTS.md` 顶部只保留红线摘要；详细规则、理由、未来的自动化检查钩子写在这里。

规则严重等级：

- 🔴 **BLOCKER**：违反则 PR 必须驳回。
- 🟠 **MAJOR**：违反需 ADR 解释，否则驳回。
- 🟡 **MINOR**：建议遵守，例外可在 PR 描述中说明。

每条规则给出：**编号 / 严重等级 / 规则 / 理由 / 检查方式（人工 or 未来自动化钩子）**。

---

## A. 架构与模块依赖

### A1 🔴 模块依赖方向不可反向
- `:diagram-core` 不得依赖任何兄弟模块。
- `:diagram-layout` 仅可依赖 `:diagram-core`。
- `:diagram-parser` 仅可依赖 `:diagram-core`；其内部 `parser.mermaid` / `parser.plantuml` / `parser.dot` 三个子包互不可见。
- `:diagram-render` 是唯一聚合点，依赖以上全部 + Compose。
- 反向（如语法依赖渲染、布局依赖语法、core 依赖 parser）一律禁止。

**理由**：保证 IR 是唯一耦合面，任何语法/算法/后端可独立替换。
**检查**：人工 review；后续加 `dependency-analysis-gradle-plugin` 强校验。

### A2 🔴 commonMain 不得使用平台 API
- `commonMain` 内禁止 `java.*` / `android.*` / `kotlinx.cinterop.*` / `org.w3c.*` / `kotlinx.browser.*`。
- 平台能力一律走 `expect / actual`，且 `actual` 文件必须落在对应平台源集。

**理由**：保证 KMP 一致性、Wasm/iOS 可编译。
**检查**：人工；可加 ktlint / detekt 自定义规则扫包名。

### A3 🟠 不得引入大三方依赖
禁止依赖列表（如有强需求需 ADR）：
- `org.apache.xmlgraphics:batik-*`
- `org.eclipse.elk:*`
- `dagre`, `graphlib`, `cytoscape`（任何 JS interop 引入）
- `org.graphviz:*`、Graphviz native 绑定
- `org.apache.poi:*`、`org.apache.pdfbox:*`
- 任何 GPL/AGPL 库

**理由**：纯 KMP 自研是项目核心定位，避免被某平台锁死或包体积爆炸。

### A4 🔴 IR / DrawCommand / 公开 API 是契约
- 修改 `docs/ir.md` 中的 IR 字段、`docs/draw-command.md` 指令集、`docs/api.md` 公开签名 → 必须配 ADR。
- 1.0 之前 minor 可破坏，但仍需 ADR 说明迁移路径。

---

## B. 解析器

### B1 🔴 解析器不抛异常
- 公开入口 `parse(source): ParseResult`，对任意输入都不允许 `throw`。
- 内部 `check`/`require`（不变式被破坏）允许 throw，但属 bug。

### B2 🔴 行驱动 / 块驱动必须保留 Span
- 每个 Token、每个 AST 节点、每条 Diagnostic 必须能定位到源文本 `Span(line, col)`。

### B3 🟠 严格兼容官方语法
- 每个图类型至少 5 个来自官方文档/Gallery 的样例落到 `commonTest/resources/<lang>/<diagram>/`。
- 不能解析时优先报 Diagnostic，不要静默忽略。

### B4 🟡 命名约定
- `*Lexer`, `*Parser`, `*Token`, `*Ast`, `*Lowering`。

---

## C. 布局

### C1 🔴 算法必须确定性
- 相同 `(model, options.seed)` 必须产生相同 `LaidOutDiagram`。
- 力导向、模拟退火等随机算法必须用 `RandomSource(seed)`，禁止使用 `kotlin.random.Random.Default` 或 `System.currentTimeMillis()`。

### C2 🟠 算法实现必有专题文档
- 新增算法 → `docs/layout/<id>.md`（参照 `docs/layout/README.md` §4 模板）。
- 复杂度、参数、退化输入、5+ 回归样例必须列出。

### C3 🟡 不在布局算法内做渲染相关取舍
- 颜色、字体、形状细节属于 Theme/渲染层；布局只关心几何。

---

## D. 渲染与导出

### D1 🔴 文本测量唯一来源
- `TextMeasurer`（commonMain 抽象）在布局阶段测量；渲染/导出阶段禁止重新测量。

### D2 🔴 三个后端共享 DrawCommand
- Compose、SVG、PNG/JPEG 必须消费同一份 `List<DrawCommand>`。
- 任何后端不得"私自加"渲染指令；新指令统一在 `docs/draw-command.md` §2 添加。

### D3 🔴 渲染对未知输入必须降级
- 未知形状 → 占位虚线框；未知颜色 → theme 默认；未知图标 → 缺省占位。
- 同时上抛 `RenderWarning`，不允许崩溃。

### D4 🟠 导出 SVG / PNG / JPEG 同模块
- 都在 `:diagram-core`，SVG 在 commonMain，位图走 `expect/actual`。

---

## E. 测试

### E1 🔴 黄金语料 + 快照不可省
- 每个图类型至少：`<case>.<ext>` + `<case>.expected.svg`。
- IR 快照可选但推荐：`<case>.expected.ir.txt`。

### E2 🔴 修 bug 先写复现样例
- 没有"裸修"，bug fix PR 必须包含一个能复现 bug 的最小语料。

---

## F. 流式增量（贯穿全 Phase）

### F1 🔴 增量契约必须满足
- 任何新增 lexer 必须实现 `ResumableLexer`：保留 `LexerState` + 报告 `safePoint`，禁止隐式回退到流头部。
- 任何新增 parser 必须按行/块边界推进，未闭合内容产 `Diagnostic.HINT` 而非 throw / 阻塞下游。
- 任何 IR 变更必须可表达为 `IrPatch`（AddNode / AddEdge / AddCluster / AddDiagnostic / 同 chunk 内 UpdateAttr）；禁止"全量替换"语义。
- 任何 layout 必须支持 `incremental = true`：已有节点坐标钉住，新节点局部追加；fallback 到全量布局必须显式 opt-in。
- 任何新 DrawCommand 必须可被会话层增量追加；UI 端必须使用 quadtree 视口剔除 + 测量缓存。

**理由**：LLM 流式是核心用例（见 `docs/streaming.md` §1），任何破坏增量性的实现都会让首屏 / 续帧时延失控。
**检查**：每个图类型 PR 必须配套 `streaming` 切片测试（详见 E1 + `docs/streaming.md` §6）。

### F2 🔴 性能预算不可破
- `session.append(<= 100 char)` 端到端 < 16ms（60fps 不掉帧）。
- `session.finish()` < 50ms。
- 内存占用 O(已接收源长度)，禁止保留全量 token 流。
- 万节点视口渲染 60fps（基于 quadtree culling）。

**检查**：`:diagram-core:jvmTest` JMH bench + `:diagram-render` Macrobenchmark；CI 红线。

### F3 🟠 NodeId 稳定派生
- 显式 ID（源文本声明）优先；匿名节点统一用 `"$anon@<sourceOffset>"` 派生。
- 同一源位置在重复 chunk 推进中必须得到同一 NodeId（布局复用前提）。

### E3 🟠 跨平台必跑
- `./gradlew allTests` 必须通过；CI 上跑 JVM + JS + Wasm + Android unit + iOSSimulator。

---

## F. 公开 API

### F1 🟠 注解与文档
- `public` 类/函数加 `@DiagramApi`（自定义注解）+ KDoc。
- KDoc 至少含一行说明 + 一段 ```kotlin``` 用法示例。
- 实验性 API 加 `@DiagramExperimental`，调用方须 opt-in。

### F2 🔴 ABI 兼容
- 1.x 阶段：minor 不能删除/改签名 `@DiagramApi`；新增字段必须有默认值。
- 任何破坏需 ADR + changelog 显著标注。

---

## G. Agent 工作流（专门给 AI agent 的硬规则）

### G1 🔴 先读路标再动手
- 任何任务起手必须读 `AGENTS.md`，再读 `docs/plan.md`、相关的 `docs/*.md`。

### G2 🔴 不得虚构模块、文件、依赖
- 不允许编造一个不存在的 Gradle 模块名、库依赖、API 名。
- 引用文件 / API / 库前必须先 view / search 确认存在。

### G3 🔴 不得修改"关键决策"
- `AGENTS.md` §2 关键决策表由用户拍板，agent 不得自行推翻或"优化"。
- 想改 → 用 `ask_user` 询问。

### G4 🔴 todo 状态实时同步 SQL
- 接手 → `in_progress`；完工 → `done`；遇阻 → `blocked` 并写明原因。

### G5 🟠 不在文档里写时间/日期估算
- 文档与计划禁止出现"X 天/X 周/Q1 完成"等时间承诺。

### G6 🟡 文档同步原则
- 实现完成同步更新：对应 `docs/syntax-compat/*.md` 状态、`AGENTS.md` §8 进度、`docs/diagnostics.md` 新码。

---

## H. 提交与 ADR

### H1 🟠 ADR 触发条件
任意一项触发就要写 ADR：
- 新增 / 删除 Gradle 模块。
- 修改 IR / DrawCommand / 公开 API。
- 引入新一级依赖。
- 偏离 `docs/plan.md` 的 Phase 顺序或范围。

### H2 🟡 提交信息格式
- `<scope>: <短标题>`，正文写背景 + 决策 + 影响。
- `Refs: phase-<n>/<todo-id>`。

---

## 未来自动化（占位）

下面这些规则将在 Phase 0 / Phase 7 加入 CI 自动校验：

- [ ] A1：Gradle 模块依赖方向校验（`dependency-analysis-gradle-plugin` 或自写脚本）。
- [ ] A2：commonMain 包名扫描（detekt 自定义规则）。
- [ ] A3：依赖白名单（`./gradlew dependencies` + 脚本 diff）。
- [ ] B1：parser API 不抛异常（静态扫描 `throw` 语句 + 反射检查）。
- [ ] D1：渲染/导出层禁止调用 `TextMeasurer`（包/import 扫描）。
- [ ] E1：黄金语料覆盖率（脚本扫描 `resources/<lang>/<diagram>/*.expected.svg`）。
- [ ] F1：`@DiagramApi` 必须有 KDoc（detekt 规则）。
- [ ] F2：ABI 比对（`binary-compatibility-validator`）。
