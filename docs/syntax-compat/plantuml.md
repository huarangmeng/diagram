# PlantUML 语法兼容矩阵

> 每实现一项就更新一行。兼容目标：直接跑 PlantUML 官方文档与 PlantUML Server 上的示例。
> 当前 PlantUML 仍为 stub pipeline：Phase 1 不交付，Phase 4 起进入实现与验收。

| 图类型 | 状态 | 关键语法 | Phase | 备注 |
|---|---|---|---|---|
| sequence | ⬜ | participant/actor/control/boundary/database, `->`/`-->`/`<<-`、autonumber、activate/deactivate、return、group/alt/opt/loop/par/critical | 4 | |
| usecase | ⬜ | actor、`(usecase)`/`usecase as`、`-->`、package、rectangle | 4 | |
| class | ⬜ | class/interface/abstract/enum、`<|--`/`*--`/`o--`/`-->`/`..>`、可见性、泛型 `<T>`、note | 4 | |
| activity (新语法) | ⬜ | `:action;`, `if/elseif/else/endif`, `while/repeat/fork/end fork`, swimlane `|name|` | 4 | |
| component | ⬜ | component/interface/port、`-->`、package、cloud、node | 4 | |
| state | ⬜ | composite、并行 `--`、history、note、`-->` | 4 | |
| object | ⬜ | object、关系、属性 | 4 | |
| deployment | ⬜ | node/artifact/database/cloud、嵌套 | 4 | |
| erd | ⬜ | entity/attribute、关系 | 4 | |
| timing | ⬜ | clock、binary、concise、robust | 5 | |
| wireframe (Salt) | ⬜ | `salt`、按钮/输入/下拉/树、Tab、frame | 5 | |
| archimate | ⬜ | archimate 元素与关系图标库 | 5 | |
| c4 (C4-PlantUML) | ⬜ | `!includeurl` C4_*、Person/System/Container/Component | 5 | |
| gantt | ⬜ | Project starts、`Task1 lasts 5 days`、依赖、节假日 | 5 | |
| mindmap | ⬜ | `*`/`**`/`+`/`-` 层级 | 5 | |
| wbs | ⬜ | wbs、层级 | 5 | |
| network (nwdiag) | ⬜ | network、address、节点 | 5 | |
| ditaa | ⬜ | ASCII art 网格识别 | 5 | |
| json / yaml | ⬜ | 嵌套结构、折叠 | 5 | |

## 公共
- `@startxxx ... @endxxx` 块识别；`!define` / `!include` 暂不支持（Phase 5+ 可选）。
- `skinparam`：合并入 `StyleHints`，常用项映射表见 `docs/theme.md`。
- 黄金语料：`composeApp/src/commonTest/resources/plantuml/<diagram>/`
