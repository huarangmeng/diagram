# PlantUML 语法兼容矩阵

> 每实现一项就更新一行。兼容目标：直接跑 PlantUML 官方文档与 PlantUML Server 上的示例。
> PlantUML 已进入 Phase 4 实现与验收；当前已落地 `sequence` / `class` / `state` / `component` / `usecase` / `activity` / `object` / `deployment` / `erd` 九条最小可用 streaming 子链路。

| 图类型 | 状态 | 关键语法 | Phase | 备注 |
|---|---|---|---|---|
| sequence | 🟡 | participant/actor/control/boundary/database/collections/queue、`->`/`<-`/`->>`/`<<-`/`-->`/`<--`/`-->>`/`<<--`、autonumber、activate/deactivate、return、note、group/alt/opt/loop/par/critical/break | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 `create/destroy/autonumber stop/resume/ref/box` |
| usecase | ✅ | actor / `actor/` / `:Actor:` / `:Actor:/`、`(usecase)` / `usecase X` / `usecase "Label" as X`、`-->` / `<--` / `..>` / `<..` / `.>` / `<.` / `--` / `..` / `<|--`、package / rectangle 嵌套、单行/多行 `note`、`include/extend` 标签归一化、常用 `skinparam actor/usecase/note/package/rectangle` 与 `ArrowColor` 颜色映射 | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；business actor 变体会渲染 actor body slash，并通过 `commonTest` 与 one-shot vs chunked 一致性校验 |
| class | ✅ | class/interface/abstract/enum、别名 `class "Label" as X`、`package`、成员块、dotted member、可见性、泛型 `<T>`、单行/多行 note、`<|--`/`--|>`/`<|..`/`..|>`/`*--`/`o--`/`-->`/`<--`/`..>`/`<..`/`--`/`..` | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；renderer 已区分 abstract/interface/enum 的头部样式，并通过 `commonTest` 与 one-shot vs chunked 一致性校验 |
| activity (新语法) | ✅ | `start` / `stop`、`:action;`、`if/else/elseif/endif`、`while/endwhile`、`repeat` / `repeat while (...)`、`fork` / `fork again` / `end fork`、swimlane `|name|`、单行/多行 `note`、legacy `(*)` / `(*top)` / quoted action / `-->` / `-right->` / `[label]` / `if "cond" then` / `"Action" as A1` / `A1 --> ...` / 同步条 `=== label ===` / `===B1=== --> ...`、`partition Name #Color { ... }`、行内 `#Color:Action;`、常用 `skinparam activity` / `skinparam Activity*Color` 颜色映射 | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；swimlane / partition 当前统一以内部 lane cluster 呈现，`repeat` 走 do-while 风格 lowering；同步条 `=== ... ===` 当前映射为 `ForkBar` 样式节点，并通过 `commonTest` 与 one-shot vs chunked 一致性校验 |
| component | ✅ | component / `[Component] as X` / interface / `()` interface / port / portin / portout / database / queue、`-->` / `<--` / `..>` / `<..` / `--` / `..`、package / cloud / node / frame / rectangle 嵌套、单行/多行 `note` | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；`portin/portout` 已按方向贴靠宿主组件边界并重写连接几何，通过 `commonTest` 与 one-shot vs chunked 一致性校验 |
| state | 🟡 | `state` / `state "desc" as X` / `state X : desc`、composite、nested composite、`[*]` / `[H]` / `[H*]`、`<<choice>>` / `<<fork>>` / `<<join>>` / `<<history>>` / `<<deep_history>>`、note、`-->`、`left to right direction`、并行分区分隔线 `--` | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前 `--` 仅做最小解析兼容，尚未建模真正 region 语义 |
| object | 🟡 | object / `object "Label" as X`、属性块 `{ ... }`、`Obj : key = value`、`-->` / `<--` / `..>` / `<..` / `--` / `..` / `<|--` / `*--` / `o--` | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 note、map/json 风格对象、package/namespace、方法调用样式与更完整实例关系语义 |
| deployment | 🟡 | node / artifact / database / cloud / frame / package、`-->` / `<--` / `..>` / `<..` / `--` / `..`、嵌套 | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖 note、actor/queue/storage 等更多部署元素、sprite/icon、port/anchor 语义与更完整实例拓扑 |
| erd | 🟡 | `entity`、属性块 `{ ... }`、`*`/`+`/`#` 属性标记、`<<PK/FK/UK>>`、crowfoot 关系如 `||--o{`、`note left/right/top/bottom of Entity : ...`、简单 `entity Foo as F` 别名 | 4 | 已接入 `Diagram.session(SourceLanguage.PLANTUML)`；当前未覆盖复杂别名、更多关系装饰、非标准 attribute 语法与更完整数据库样式体系 |
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
- `skinparam` 当前仅 `activity` 与 `usecase` 子链路支持常用颜色项映射到 `StyleHints`；其他图型仍记录 `PLANTUML-W001` 并忽略。
- 黄金语料：`composeApp/src/commonTest/resources/plantuml/<diagram>/`
