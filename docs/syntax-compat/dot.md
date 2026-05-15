# Graphviz DOT 语法兼容矩阵

> 兼容目标：能解析 Graphviz Gallery 大多数 .dot 示例并合理布局。
> 当前 DOT 已进入 Phase 6：`SourceLanguage.DOT` 会分发到真实 DOT parser + GraphIR/Sugiyama 渲染链路。

| 特性 | 状态 | 子项 | Phase | 备注 |
|---|---|---|---|---|
| 顶层声明 | ✅ | `digraph`, `graph`, `strict` | 6 | `graph [label=...]` 优先作为标题 |
| 子图 / 集群 | ✅ | `subgraph`, `subgraph cluster_*` | 6 | `cluster_*` 降为 `GraphIR.Cluster` 并渲染 cluster rect；普通 subgraph 会展开 children |
| 节点 | ✅ | id 引号 / Unicode、HTML-like label `<...>` | 6 | HTML-like label 会清洗为纯文本，并保留 FONT/B/I 等文本样式提示 |
| 边 | ✅ | `->` / `--`、edge chaining `a -> b -> c`、`{a b} -> {c d}` 集合边、`subgraph` edge operand 子集 | 6 | 集合边按相邻 operand 做笛卡尔展开 |
| 端口 | ✅ | `node:port:compass` (n/ne/e/se/s/sw/w/nw) | 6 | port/compass 已保留到 edge payload，渲染端按 compass 改写路径首尾锚点 |
| 节点属性 | ✅ | shape、style(`filled`/`dashed`/`rounded`/`bold`/`italic`)、color、fillcolor、fontname/fontsize、fontcolor、label、tooltip、URL/href | 6 | 视觉属性已生效；tooltip 保留 payload；URL/href 渲染为 `DrawCommand.Hyperlink` |
| 边属性 | ✅ | label/headlabel/taillabel、arrowhead/arrowtail、style、color、penwidth、constraint、weight | 6 | label/headlabel/taillabel 与箭头/线型已渲染；`constraint=false` 不参与分层；weight 保留 payload |
| 图属性 | ✅ | rankdir(LR/TB/RL/BT)、ranksep、nodesep、splines、bgcolor | 6 | rankdir/ranksep/nodesep/bgcolor 已生效；splines 作为提示保留，统一走内部 Bezier/Sugiyama routing；final reflow 会按邻居重心居中较窄 rank |
| Rank 约束 | ✅ | `{rank=same; a; b}`, `rank=min/max/source/sink` | 6 | full reflow 阶段会强制调整 Sugiyama layer；streaming 增量阶段仍保持 pinned layout |
| HTML-like label | ✅ | TABLE/TR/TD、PORT、IMG、字体修饰 | 6 | TABLE/TR/TD/BR 会清洗为多行纯文本并解码基础 entity；FONT/B/I 会映射到文本字体/字号/颜色/粗斜体；PORT/IMG 作为文本兼容，不做嵌入图片/table cell layout |
| 注释 | ✅ | `//`, `/* */`, `#` 行首 | 6 | |
| Streaming 增量 | ✅ | statement-level parser、`SessionPatch.addedDrawCommands` delta | 6 | DOT session 按 `;` / `}` / 换行 safe point 推进完整 statement，不再在 append 时对累计源码做 `source.toString()` 全量解析；渲染 patch 通过 `DrawCommandStore` 避免空闲 append 重放整帧 |
| 不支持 | — | `neato/fdp/twopi/circo` 专属布局指令视为提示，统一走 Sugiyama；记录 RenderWarning | 6 | |

## 文档参考
- 官方：https://graphviz.org/doc/info/lang.html
- 属性表：https://graphviz.org/doc/info/attrs.html
- 黄金语料：`composeApp/src/commonTest/resources/dot/`
