# 架构设计

## 0. 设计目标
1. **单一中间表示**：三种语法都折叠到同一族 IR，下游布局/渲染/导出对语法零感知。
2. **渲染与平台解耦**：Compose / SVG / PNG 共用一套 `DrawCommand` 指令流。
3. **算法纯 KMP**：解析、布局、布局后几何计算一律 commonMain，零反射、零 JVM 专属 API。
4. **可插拔**：新增一种图类型 = 新加一个 lowering + 选择已有布局算法；不需要改任何渲染/导出代码。

## 1. 数据流总览

```
源文本 (String)
  │
  ▼
Diagram.parse(text)            // :diagram-render 入口，按首行/魔术关键字分发
  │
  ▼
LanguageDispatcher
  ├─► MermaidParser   ─┐
  ├─► PlantUmlParser  ─┤  ──►  ParseResult(model, diagnostics)
  └─► DotParser       ─┘
                          model: DiagramModel（IR sealed family）
  │
  ▼
LayoutEngine.layout(model, options)
  └─► 按 IR 类型选算法 → LaidOutDiagram（带几何坐标）
  │
  ▼
RenderPlan.from(laidOut, theme)        // 生成 List<DrawCommand>
  │
  ├─► ComposeRenderer.draw(drawScope)   // :diagram-render
  ├─► SvgWriter.write(): String         // :diagram-core
  └─► PlatformCanvas.rasterize() → PNG  // :diagram-core expect/actual
```

## 2. 模块边界

| 模块 | 依赖（仅可依赖以下） | 职责 |
|---|---|---|
| `:diagram-core` | kotlin stdlib, kotlinx-* | IR、几何、Theme、DrawCommand、Diagnostics、SVG / PNG / JPEG 导出 |
| `:diagram-layout` | `:diagram-core` | 布局算法集合 |
| `:diagram-parser` | `:diagram-core` | 三家语法（Mermaid / PlantUML / DOT）文本 → IR；按子包 `parser.{mermaid,plantuml,dot}` 隔离，子包之间不得互相 import |
| `:diagram-render` | `:diagram-core`, `:diagram-layout`, `:diagram-parser`, Compose | DrawCommand → Canvas、交互 + 顶层门面 `Diagram.parse` / `DiagramView` |
| `:composeApp` / `:androidApp` | `:diagram-render` | demo gallery |

> 反向依赖**禁止**：例如 `:diagram-parser` 不能依赖布局或渲染；`:diagram-layout` 不能依赖语法；`:diagram-core` 不能依赖任何兄弟模块。`:diagram-parser` 内部 `mermaid` / `plantuml` / `dot` 三个子包互不可见。

## 3. 关键扩展点

### 3.1 新增一种图类型
1. 在 `:diagram-core` 新增（或复用）一个 `DiagramModel` 子类。
2. 在对应语法子模块写 lowering：tokens → AST → IR。
3. 在 `:diagram-layout` 选用现有算法或新增算法。
4. 在 `:diagram-render` 注册节点形状（如需新形状）；其它走通用 DrawCommand。
5. 添加黄金语料 + 快照测试。
6. 更新 `docs/syntax-compat/<lang>.md`。

### 3.2 新增一种布局算法
- 实现 `IncrementalLayout<I>` 接口（`:diagram-layout`），注册到 `LayoutRegistry`。
- **必须支持 streaming pinning**：当 `previous != null && options.incremental` 时，已存在的 `(NodeId, Rect)` 必须 byte-for-byte 等于 baseline；详见 `docs/streaming.md` §3.4。
- 在 `docs/layout/<algo>.md` 写设计 + 复杂度 + 适用图类型 + 增量策略。

### 3.3 新增导出格式
- 在 `:diagram-core` 增加 writer，消费 `List<DrawCommand>`，无需碰其它模块。

## 4. 线程与并发
- 解析、布局是 CPU 密集 → 在调用方用 `Dispatchers.Default` 包；库内部不自启协程。
- 渲染在 Compose 的 UI 线程；命中检测用预构建的 quadtree（在布局后一次性构建）。

## 5. 错误模型
- 解析器**绝不抛异常**，返回 `ParseResult(model: DiagramModel?, diagnostics: List<Diagnostic>)`。
- 渲染器对未知节点/形状/颜色降级到默认占位（绘制虚线框 + 警告色），并通过 `RenderWarnings` 上抛。
- `DiagramView` 默认在 UI 上叠加错误浮层，可通过 `errorPolicy` 关闭。

## 6. 性能目标（参考线，会随 Phase 校准）
- 100 节点 flowchart：解析 + 布局 < 50ms（JVM）/ < 150ms（Wasm）。
- 1000 节点：分层布局 < 1s（JVM），交互 60fps（quadtree 命中 < 1ms）。
- SVG 字符串生成：100 节点 < 20ms。

## 7. 不变式（Invariants）
- IR 一律 `data class` / `sealed`，**不可变**。
- 同一 `DiagramModel` 在相同 `LayoutOptions` 下布局结果**确定**（确定性种子的力导向算法亦然）。
- `DrawCommand` 流是布局结果的**纯函数**，可缓存、可序列化。
