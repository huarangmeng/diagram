# 贡献指南（含 Agent 工作流）

## 1. 开始之前
1. 读 `../AGENTS.md`。
2. 读 `plan.md` 找到要做的 Phase / todo。
3. SQL `todos` 表里把要做的项设为 `in_progress`。

## 2. 新增一种图类型（最常见）

以"在 Phase 2 加 Mermaid `pie`"为例：

1. **IR**：`:diagram-core` 内确认/新增 `PieIR`（在 `core/ir/PieIR.kt`），更新 `docs/ir.md` §2 表格。
2. **解析器**：`:diagram-parser` 内 `parsers/PieParser.kt`，挂到 `MermaidParser` 的 dispatch（按首行 `pie` 关键字）。
3. **黄金语料**：`commonTest/resources/mermaid/pie/<case>.mmd` + `.expected.ir.txt`。
4. **布局**：`:diagram-layout` 选 `grid-pack` 算法，必要时加专用算法（`PieLayout`）。
5. **渲染**：若不需要新形状，复用现有 DrawCommand；否则在 `core/draw/Shapes.kt` 新增形状。
6. **SVG 快照**：`.expected.svg` 落盘。
7. **更新文档**：`docs/syntax-compat/mermaid.md` 把 pie 状态从 ⬜ → ✅/🟡。
8. SQL：todo → `done`。

## 3. 新增一种语法（罕见）

1. 起一个 `:diagram-<lang>` 模块，仅依赖 `:diagram-core`。
2. 在 `:diagram-render` 的 `LanguageDispatcher` 注册识别规则。
3. 写 `<Lang>Lexer` + `<Lang>Parser`，每个图类型一个 sub-parser。
4. 给每个图类型挂到现有 IR；不能挂的先在 `docs/ir.md` 提案新 IR 家族。
5. 创建 `docs/syntax-compat/<lang>.md`。

## 4. 新增一种布局算法

1. 实现 `Layout<I, O>`，注册到 `LayoutRegistry`。
2. 必须**确定性**：相同输入 + 相同 `seed` → 相同输出。
3. `docs/layout/<algo>.md` 写设计文档。
4. 至少 5 个回归样例 + 坐标快照。

## 5. 代码风格 checklist
- [ ] 没有 `Util/Helper/Manager` 命名
- [ ] 公开 API 加 `@DiagramApi` + KDoc + 用法示例
- [ ] 解析器返回 `ParseResult`，不抛异常
- [ ] 渲染器对未知输入降级 + `RenderWarning`
- [ ] 跨平台代码在 `commonMain`；平台 API 限定在 `expect/actual`
- [ ] 黄金语料 + 快照齐全
- [ ] `docs/syntax-compat/*.md` / `docs/ir.md` / `docs/layout/*.md` 同步更新

## 6. 提交信息
```
<scope>: <短标题>

正文（可选）：背景、决策、影响。

Refs: phase-<n>/<todo-id>
```

`<scope>` 用模块短名：`core/layout/render/export/mermaid/plantuml/dot/api/demo/docs`。
