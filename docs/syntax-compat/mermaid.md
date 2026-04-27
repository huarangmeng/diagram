# Mermaid 语法兼容矩阵

> 每实现一项就更新一行；`✅` = 完整、`🟡` = 部分（在备注列出限制）、`⬜` = 未开始。
> 兼容目标：直接跑 Mermaid 官方文档示例。

| 图类型 | 状态 | 关键修饰符 / 子语法 | Phase | 备注 |
|---|---|---|---|---|
| flowchart | ✅ | direction, shapes(`[]`/`()`/`{}`/`[//]` 等), 边类型(`-->`/`---`/`-.->`/`==>`)、subgraph、classDef、style、linkStyle、click | 1 | |
| sequenceDiagram | ✅ | participant/actor、自激活、`->>` 系列、note、loop/alt/opt/par/critical/break、autonumber | 1 | |
| classDiagram | ✅ | 关系(`<|--`/`*--`/`o--`/`-->`/`..>`/`..|>`)、可见性、泛型、接口、注解、namespace | 1 | |
| stateDiagram-v2 | ✅ | composite、并行、history、`[*]`、note、direction | 1 | |
| erDiagram | 🟡 | 实体属性、关系基数、PK/FK | 1 | 当前渲染为“实体节点 + 属性节点”连接，后续可升级为实体内嵌属性展示 |
| gantt | ⬜ | dateFormat、section、task、依赖 `after`、milestone、excludes | 2 | |
| timeline | ⬜ | section、events | 2 | |
| pie | ⬜ | showData、title、slice | 2 | |
| gauge | ⬜ | value、min/max | 2 | |
| journey | ⬜ | section、task with score | 2 | |
| mindmap | ⬜ | 缩进、节点形状、icon、class | 2 | |
| xyChart | ⬜ | line/bar/scatter、x/yAxis、title | 2 | |
| sankey | ⬜ | source,target,value | 2 | |
| kanban | ⬜ | column、card metadata | 2 | |
| gitGraph | ⬜ | commit、branch、merge、cherry-pick、tag | 2 | |
| requirementDiagram | ⬜ | requirement/element/relation | 3 | |
| architectureDiagram | ⬜ | service、group、edge with port、icon | 3 | |
| c4 | ⬜ | C4Context/Container/Component/Code/Deployment、System_Boundary | 3 | |
| block | ⬜ | block-beta、columns、空槽、合并 | 3 | |

## 文档参考
- 官方：https://mermaid.js.org/intro/syntax-reference.html
- 黄金语料目录：`composeApp/src/commonTest/resources/mermaid/<diagram>/`

## Styling Notes
- Mermaid 支持引用外部 CSS class（如 `.cssClass > rect { ... }`）作为样式来源；本项目暂不支持外部 CSS，遇到此类用法将忽略并产出 `MERMAID-W010` 警告。
- Mermaid 样式对齐方案详见 `docs/style-mermaid.md`。
