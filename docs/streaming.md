# 流式增量解析与渲染（Streaming / Incremental）

> 本框架的 **一等公民** 用例：LLM 流式输出 Mermaid / PlantUML / DOT 文本，框架边接收边渲染。
> 所有 Phase 的 lexer / parser / IR / layout / render 实现都必须满足本文规约。

---

## 1. 场景假设（已与用户确认）

- **Append-only 文本流**：`source` 字符串只在末尾追加，已送出的字符不会被改写。
  （任意位置编辑暂不在本期范围；未来扩展不影响本期 API。）
- **粒度未知**：每个 chunk 可能只是几个字符，也可能是整段；可能在词法/语法/块结构的任意位置切断。
- **partial 永远合法**：流到任意时刻调用渲染，都必须输出"截至此刻可渲染的最大子图"，并把未闭合块作为软诊断暴露，而不是硬错误。
- **目标**：每次 `append(chunk)` 在主流程下 **< 16 ms**（60fps 不掉帧），整个会话内存占用与已接收文本长度近似线性。

---

## 2. 核心 API：DiagramSession

```kotlin
// :diagram-render（顶层门面）
class DiagramSession internal constructor(
    val language: SourceLanguage,
    val theme: DiagramTheme,
    val layoutOptions: LayoutOptions,
) {
    /** 累积已接收的源文本（只增不减，便于 diagnostic span 还原）。 */
    val source: CharSequence

    /** 当前快照：可订阅的 StateFlow，UI 层 collectAsState 即可。 */
    val state: StateFlow<DiagramSnapshot>

    /** 推入新 chunk；返回本次产生的增量 patch（也会推到 state）。 */
    fun append(chunk: CharSequence): SessionPatch

    /** 完成（LLM 流结束）；触发"最终化"——尝试关闭未闭合块、稳定布局、生成最终 DrawCommand。 */
    fun finish(): DiagramSnapshot

    /** 释放内部缓存。 */
    fun close()
}

data class DiagramSnapshot(
    val ir: DiagramModel?,                  // 当前已解析出的 IR（可能为 partial）
    val drawCommands: List<DrawCommand>,    // 已渲染的指令流
    val diagnostics: List<Diagnostic>,      // 累积诊断（含 partial 提示）
    val seq: Long,                          // 单调递增版本号
    val isFinal: Boolean,
)

data class SessionPatch(
    val addedNodes: List<NodeId>,
    val addedEdges: List<Edge>,
    val addedDrawCommands: List<DrawCommand>,
    val newDiagnostics: List<Diagnostic>,
)
```

入口：

```kotlin
val session = Diagram.session(
    language = SourceLanguage.MERMAID,        // 或 AUTO 让框架按首行猜
    theme = DiagramTheme.Default,
)
flow.collect { chunk -> session.append(chunk) }
session.finish()
```

Compose 侧：

```kotlin
@Composable
fun StreamingDiagramView(session: DiagramSession, modifier: Modifier = Modifier) {
    val snapshot by session.state.collectAsState()
    DiagramView(snapshot = snapshot, modifier = modifier)
}
```

---

## 3. 五层增量化契约

每一层都必须能从"上次状态 + 新增输入"推进到"新状态"，禁止重新跑全量。

### 3.1 Lexer（resumable）

```kotlin
interface ResumableLexer {
    fun initialState(): LexerState
    /** 从 state 开始，对 input 增量产出 token；返回新 state（可序列化、不可变）。 */
    fun feed(state: LexerState, input: CharSequence, offset: Int): LexerStep
}

data class LexerStep(
    val tokens: List<Token>,          // 本轮新产出的 token
    val newState: LexerState,         // 喂下一段时使用
    val safePoint: Int,               // 已稳定消费到的源 offset（之后可丢弃）
)
```

要点：
- `LexerState` 包含模式栈（如 PlantUML 内 `@startuml/@startsalt` 区块切换）、字符串/注释续行标志、缩进栈。
- **safePoint**：lexer 应在每个换行 / `;` / 已闭合的引号外侧给出 safePoint，告诉上层"在这之前的源不会再被回退重读"。
- 永远不要假设输入是完整 UTF-8 字符；末尾不完整的 code unit 留给下一轮（保留在 `newState`）。

### 3.2 Parser（line/block 增量 + 错误恢复）

- 三家语法都按 **行/块边界** 增量：
  - Mermaid：行驱动，每行是一条独立语句；多行块（如 `subgraph...end`、`gantt section`）以关键字配对。
  - PlantUML：`@startxxx ... @endxxx` 块；块内仍按行增量。
  - DOT：以 `;` 或 `}` 为语句边界（C 风格）。
- Parser 维护 `ParserState`，仅在新行/新语句完整接收时推进；未闭合行留在 buffer 里等待下一 chunk。
- **partial AST 永远可读**：未闭合块产出 `Diagnostic.Severity.HINT("block @startuml not yet closed")`，不阻塞下游。
- 错误恢复策略：行级 panic-mode——遇到无法识别的 token，跳到下一个行边界继续，丢失的语句记录为 ERROR，已成功的语句保留。

### 3.3 IR（append-only patch）

```kotlin
sealed interface IrPatch {
    data class AddNode(val node: Node) : IrPatch
    data class AddEdge(val edge: Edge) : IrPatch
    data class AddCluster(val cluster: Cluster) : IrPatch
    data class UpdateAttr(val target: NodeId, val style: NodeStyle) : IrPatch  // 仅同 chunk 内 lower 阶段产生
    data class AddDiagnostic(val d: Diagnostic) : IrPatch
}
```

- IR 在会话生命周期内只增不减；finish() 时可能做一次"提升"（如 partial 占位节点替换为最终节点）。
- **稳定 ID**：`NodeId` 优先使用源文本里显式声明的标识符；匿名节点用 `"$anon@<sourceOffset>"` 派生，确保同一源位置在重复推进中得到同一 ID（布局复用前提）。

### 3.4 Layout（incremental + pinned）

- 默认策略：**Pinned + Append**——已布局节点坐标钉住，新增节点在已有图旁追加（同层增宽 / 新层下沉）。
- Sugiyama 实现拆为四阶段，每个阶段都要支持 "在上轮结果之上扩展"：
  1. 分层（rank）：新节点按其依赖关系分配 rank；如新增依赖打破已有 rank（罕见），降级为局部重排（仅受影响子图）。
  2. 顺序（order）：新节点尾部追加；触发 1 轮 barycenter 局部交叉缩减（仅在受影响层）。
  3. 坐标分配（x）：增量 Brandes-Köpf——已有节点 x 不动，新节点用同算法补齐。
  4. 边路由：仅对新边 + 受新节点影响的旧边重路。
- finish() 时可选触发一次 **finalize pass**：在已有坐标基础上做轻量优化（≤ 10ms）以提升美观；保证不会让节点跳变超过 1 个网格单位。
- `LayoutOptions.incremental: Boolean`（默认 true）允许调用方关闭以做完整布局。

### 3.5 Render（DrawCommand 增量 + Compose 复用）

- `SessionPatch.addedDrawCommands` 仅含新增；UI 层在 Canvas 内做 `key()` 化，已绘节点用 `Modifier.drawWithCache` 复用 path。
- `:diagram-render` 使用 `DrawCommandStore` 维护 session-local 完整帧与增量帧：现有 renderer 可通过 full-frame seam 迁移，新增/重构 renderer 应优先使用 stable entity key（node/edge/cluster）提交 delta，避免把已存在图元的坐标/样式更新误报为新增。
- 文本测量缓存：`TextMeasurer` 结果以 `(text, style, maxWidth)` 三元组为键缓存，避免重复测量。
- 视口剔除（viewport culling）：基于四叉树空间索引，仅绘制可见区域 DrawCommand；在万节点场景仍可保持 60fps。

---

## 4. 性能预算（必达）

| 操作 | 预算 | 说明 |
|---|---|---|
| `session.append(<= 100 char)` 端到端 | **< 16 ms** | 含 lex + parse + IR patch + layout patch + draw patch 推送到 StateFlow |
| `session.finish()` | **< 50 ms** | 含 finalize layout + 全图 DrawCommand 重整 |
| 文本测量 | **首次 < 1ms / 同 key 复用 0** | TextMeasurer 缓存 |
| 万节点视口渲染 | **60fps** | 基于 quadtree culling |
| 内存 | **O(已接收源长度)** | 不允许保留原始全量 token 流 |

性能基线测试在 `:diagram-core:jvmTest` + `:diagram-render:androidUnitTest` 双跑（jvm 跑解析/布局；android 跑 Canvas 渲染基线）。

---

## 5. 失败/回退策略

- **lexer/parser 卡死**（异常输入导致死循环）：每 `append` 设软性 16ms watchdog，超时则把当前 chunk 标 ERROR，回退 lexer 到上一 safePoint，丢弃已 buffer 但未行结束的部分。
- **布局打破单调性**（极端情况下增量算法把已固定节点要求移动）：fallback 到全量布局，但仅当用户 opt-in（`LayoutOptions.allowGlobalReflow = true`）；默认场景宁可丑也不要跳变。
- **DrawCommand 数量爆炸**：超过 `LayoutOptions.maxCanvas.commandBudget`（默认 50000）时停止追加新指令，输出 Diagnostic.WARN，UI 提示"图过大"。

---

## 6. 测试策略

- **黄金语料 + 切片测试**：每条样例切成 1/2/N 字符的随机 chunk 序列喂入；最终 IR / DrawCommand 必须与一次性喂入完全一致。
- **快照 + diff**：每个 patch 序列化的累计快照与 baseline 对比，定位回归。
- **Bench**：JMH（jvm）测 `append` 时延 P50/P95/P99；Android Macrobenchmark 测渲染帧率。

---

## 7. 与 ADR 关联

- 本文档驱动新 ADR：`docs/adr/0004-streaming-incremental-pipeline.md`（待写）。
- 影响契约：`docs/api.md` §1（新增 `Diagram.session`）、`docs/architecture.md` §3（新增"会话生命周期"小节）、`docs/rules.md` 新增 §A6 "增量契约必须满足"。
