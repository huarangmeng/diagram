# Graphviz DOT 语法兼容矩阵

> 兼容目标：能解析 Graphviz Gallery 大多数 .dot 示例并合理布局。

| 特性 | 状态 | 子项 | Phase | 备注 |
|---|---|---|---|---|
| 顶层声明 | ⬜ | `digraph`, `graph`, `strict` | 6 | |
| 子图 / 集群 | ⬜ | `subgraph`, `subgraph cluster_*` | 6 | 嵌套支持 |
| 节点 | ⬜ | id 引号 / Unicode、HTML-like label `<...>` | 6 | |
| 边 | ⬜ | `->` / `--`、edge chaining `a -> b -> c`、`{a b} -> c` | 6 | |
| 端口 | ⬜ | `node:port:compass` (n/ne/e/se/s/sw/w/nw) | 6 | |
| 节点属性 | ⬜ | shape、style(`filled`/`dashed`/`rounded`)、color、fillcolor、fontname/fontsize、label、tooltip、URL | 6 | |
| 边属性 | ⬜ | label/headlabel/taillabel、arrowhead/arrowtail、style、color、penwidth、constraint、weight | 6 | |
| 图属性 | ⬜ | rankdir(LR/TB/RL/BT)、ranksep、nodesep、splines、bgcolor | 6 | |
| Rank 约束 | ⬜ | `{rank=same; a; b}`, `rank=min/max/source/sink` | 6 | |
| HTML-like label | ⬜ | TABLE/TR/TD、PORT、IMG、字体修饰 | 6 | 子集 |
| 注释 | ⬜ | `//`, `/* */`, `#` 行首 | 6 | |
| 不支持 | — | `neato/fdp/twopi/circo` 专属布局指令视为提示，统一走 Sugiyama；记录 RenderWarning | 6 | |

## 文档参考
- 官方：https://graphviz.org/doc/info/lang.html
- 属性表：https://graphviz.org/doc/info/attrs.html
- 黄金语料：`composeApp/src/commonTest/resources/dot/`
