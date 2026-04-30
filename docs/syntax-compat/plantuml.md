# PlantUML 语法兼容矩阵

> 每实现一项就更新一行。兼容目标：直接跑 PlantUML 官方文档与 PlantUML Server 上的示例。
> PlantUML 已进入 Phase 4 实现与验收；当前已落地 `sequence` / `class` / `state` / `component` / `usecase` / `activity` 六条最小可用 streaming 子链路。

| 图类型 | 状态 | 关键语法 | Phase | 备注 |
|---|---|---|---|---|
| sequence | 🟡 | participant/actor/control/boundary/database/collections/queue、`->`/`<-`/`->>`/`<<-`/`-->`/`<--`/`-->>`/`<<--`、autonumber、activate/deactivate、return、note、group/alt/opt/loop/par/critical/break | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 `create/destroy/autonumber stop/resume/ref/box` |
| usecase | 🟡 | actor / `:Actor:`、`(usecase)` / `usecase X` / `usecase "Label" as X`、`-->` / `<--` / `..>` / `<..` / `--` / `..` / `<|--`、package / rectangle 嵌套 | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 note、`/` 分隔 actor 变体、真正的 include/extend 语义建模与更完整样式体系 |
| class | 🟡 | class/interface/abstract/enum、`<|--`/`<|..`/`*--`/`o--`/`-->`/`..>`/`--`/`..`、成员块、dotted member、可见性、泛型 `<T>`、note | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖别名、package、复杂 note 块、多态样式与更完整箭头变体 |
| activity (新语法) | 🟡 | `start` / `stop`、`:action;`、`if/else/endif`、`while/endwhile`、`note` | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 `elseif`、`repeat`、`fork/end fork`、swimlane `|name|` 与更完整活动图语法 |
| component | 🟡 | component / `[Component] as X` / interface / port / portin / portout、`-->` / `<--` / `..>` / `<..` / `--` / `..`、package / cloud / node 嵌套 | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 `() interface` 简写、database/queue/frame/rectangle、真实 port 锚点与 note |
| state | 🟡 | `state` / `state "desc" as X` / `state X : desc`、composite、nested composite、`[*]` / `[H]` / `[H*]`、`<<choice>>` / `<<fork>>` / `<<join>>` / `<<history>>` / `<<deep_history>>`、note、`-->`、`left to right direction`、并行分区分隔线 `--` | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前 `--` 仅做最小解析兼容，尚未建模真正 region 语义 |
| object | 🟡 | object / `object "Label" as X`、属性块 `{ ... }`、`Obj : key = value`、`-->` / `<--` / `..>` / `<..` / `--` / `..` / `<|--` / `*--` / `o--` | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 note、map/json 风格对象、package/namespace、方法调用样式与更完整实例关系语义 |
| deployment | 🟡 | node / artifact / database / cloud / frame / package、`-->` / `<--` / `..>` / `<..` / `--` / `..`、嵌套 | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 note、actor/queue/storage 等更多部署元素、sprite/icon、port/anchor 语义与更完整实例拓扑 |
| erd | 🟡 | `entity`、属性块 `{ ... }`、`*`/`+`/`#` 属性标记、`<<PK/FK/UK>>`、crowfoot 关系如 `||--o{` | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 note、复杂别名、更多关系装饰、非标准 attribute 语法与更完整数据库样式体系 |
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
- `@startuml ... @enduml` 已识别；当前 `@startgantt` / `@startsalt` / `@startjson` 等专用块仍待 Phase 5。
- `skinparam` 当前仅记录 `PLANTUML-W001` 并忽略；常用项映射到 `StyleHints` 仍待后续补齐。
- 黄金语料：`composeApp/src/commonTest/resources/plantuml/<diagram>/`
