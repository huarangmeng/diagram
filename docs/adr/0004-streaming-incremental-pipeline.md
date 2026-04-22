# 0004: 流式增量管线（Streaming / Incremental Pipeline）作为一等公民

- 状态：Accepted
- 日期：本次会话
- 关联：`docs/streaming.md`、`docs/plan.md` §1.1、`docs/rules.md` §F、AGENTS.md MUST NOT #9

## 背景
原计划隐含假设"一次性接收完整源文本 → 完整解析 → 完整渲染"。
但本框架的核心使用场景是 **LLM 流式输出 .mmd / .puml / .dot 文本**：

- 文本只在末尾追加（append-only），可能在词法/语法/块结构的任意位置切断。
- 用户期望"边接收边看"，每次 token 进来都能立刻看到增量绘制。
- 任何"全量重跑"方案在 LLM 长输出场景下都会让首屏/续帧时延爆炸。

## 选项
- **A：仅在顶层做"防抖 + 全量重解析"** —— 实现最简，但每 chunk 都要 re-lex / re-parse / re-layout，N²，且会让已绘节点跳变。
- **B：只在 parser 层做增量，layout / render 仍全量** —— 解析省时但布局抖动严重。
- **C：整链路 resumable + incremental + pinned**（lexer / parser / IR / layout / render 全部支持增量）。

## 决定
选 C。

## 理由
- LLM 是核心用例，性能预算极严（每 chunk < 16ms / finish < 50ms）。
- pinned 布局是用户体验关键：已绘节点不跳变，新节点动画淡入。
- 整链路一致才能避免"某一层成为瓶颈"，A/B 方案都会被某一段拖死。

## 影响
- **API**：新增 `Diagram.session()` / `DiagramSession.append()` / `finish()` / `state: StateFlow<DiagramSnapshot>`，与原有 `Diagram.parse()` 一次性 API 并存。
- **架构**：所有 lexer 必须实现 `ResumableLexer`；parser 必须按行/块边界推进；IR 用 `IrPatch` 表达；layout 用 `incremental = true` 默认；render 端必须 quadtree culling + 测量缓存。
- **规则**：AGENTS.md MUST NOT 新增第 9 条；rules.md 新增 §F 三条规则（增量契约 / 性能预算 / NodeId 稳定）。
- **测试**：每个图类型 PR 必须配套"流式切片测试"——把样例切成 1/2/N 字符随机 chunk 喂入，最终 IR / DrawCommand 与一次性结果完全等价。
- **不利点**：实现复杂度↑↑（Sugiyama 增量化、quadtree 维护、状态序列化）；commonMain 需要 stable id 派生策略。
- **预防回退**：本 ADR 通过后，凡新增"只能吃全文"的 lexer/parser/layout/render 一律 PR 拒收。

## 后续
- Phase 1 起，每个语法子模块的第一个 PR 必须是 `ResumableLexer` + 行/块增量 parser；
- `:diagram-render` 在 Phase 1 中期落 `DiagramSession` 顶层 API；
- `:diagram-layout` Phase 1 同步落 incremental Sugiyama 草稿。
