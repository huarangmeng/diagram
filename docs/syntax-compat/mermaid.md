# Mermaid 语法兼容矩阵

> 每实现一项就更新一行；`✅` = 完整、`🟡` = 部分（在备注列出限制）、`⬜` = 未开始。
> 兼容目标：直接跑 Mermaid 官方文档示例。

| 图类型 | 状态 | 关键修饰符 / 子语法 | Phase | 备注 |
|---|---|---|---|---|
| flowchart | ✅ | direction, shapes(`[]`/`()`/`{}`/`[//]` 等), 边类型(`-->`/`---`/`-.->`/`==>`)、subgraph、classDef、style、linkStyle、click | 1 | |
| sequenceDiagram | ✅ | participant/actor、自激活、`->>` 系列、note、loop/alt/opt/par/critical/break、autonumber | 1 | |
| classDiagram | ✅ | 关系(`<|--`/`*--`/`o--`/`-->`/`..>`/`..|>`)、可见性、泛型、接口、注解、namespace | 1 | |
| stateDiagram-v2 | ✅ | composite、并行、history、`[*]`、note、direction | 1 | |
| erDiagram | ✅ | 实体属性、关系基数、PK/FK/UK | 1 | 最终态（`finish()`）渲染为“实体框内嵌属性列表”，PK/FK/UK 以徽标展示；增量态为保证 pinned layout 与 append-only IR，内部仍会生成属性节点与属性连线，但会在最终渲染阶段折叠隐藏 |
| gantt | ✅ | dateFormat、axisFormat、tickInterval、section、task、依赖 `after`、`until`、milestone、vert、excludes、compact、click | 2 | 已支持 Mermaid `dateFormat` 官方 token 集（含 `YYYY/YY/Q/M/MM/MMM/MMMM/D/DD/Do/DDD/DDDD/H/HH/h/hh/m/mm/s/ss/S/SS/SSS/A/a/Z/ZZ/X/x`）、`click href` 与 `click call`（后者降级为 `javascript:` 外链）、`displayMode: compact`、轴刻度配置、跨图 body 的 `vert` 参考线，以及基于起点按日历月/年推进的 `duration M/y` 精确语义；`MMM/MMMM` 当前按英文月份名解析 |
| timeline | ✅ | title、section、events、`timeline TD/LR`、frontmatter `disableMulticolor` / `themeVariables` | 2 | 支持 `time : e1 : e2` 与续行 `: e3`；frontmatter 图级配置与主题变量已接入渲染 |
| pie | ✅ | title、slice（`"Label" : number`）、streaming 一致性 | 2 | |
| gauge | ✅ | title、min/max/value、streaming 一致性 | 2 | |
| journey | ✅ | title、section、task with score、actors | 2 | 采用阶段列 + 评分轨道渲染，任务卡会展示参与角色，并以折线串联步骤评分走势 |
| mindmap | ✅ | 缩进树、默认节点、`[]`/`()`/`(( ))`/`)) ((`、`) (`、`{{ }}`、`::icon(...)` | 2 | 已支持 bang / cloud 与双侧布局；`DrawIcon` 会输出结构化图标命令，未集成宿主 icon font 时以回退字样显示；`:::` class 行仍忽略并记 warning |
| xyChart | ✅ | `xychart`/`xychart-beta`、line/bar/scatter/area、x/yAxis、title、horizontal、frontmatter `showDataLabel` / `themeVariables` | 2 | 当前支持分类 x 轴和数值范围 x 轴；log/time axis 仍未实现 |
| quadrantChart | ✅ | title、x/y-axis、quadrant-1..4、points、点内联样式、frontmatter `themeVariables` | 2 | 支持 `color/radius/stroke-color/stroke-width`；`classDef` / `:::class` 当前忽略并记 warning |
| sankey | ✅ | `sankey`/`sankey-beta`、source,target,value | 2 | 支持基础 CSV 流量行与 streaming 一致性；当前未接入更细的 frontmatter/config 视觉开关 |
| kanban | ✅ | column、card、`@{ assigned/ticket/priority }` metadata、frontmatter `ticketBaseUrl` | 2 | `priority` 已做 badge 化展示；`ticketBaseUrl` 会在渲染阶段生成外链命令 |
| gitGraph | ✅ | `gitGraph`、commit、branch、checkout/switch、merge、cherry-pick、id/type/tag | 2 | 当前聚焦 LR 主布局；`commit/merge` 的 `id/type/tag` 已支持，`cherry-pick` 支持按已存在提交 id 生成摘樱桃提交 |
| requirementDiagram | ✅ | direction、requirement/function/interface/performance/physical/designConstraint、element、relation、style/classDef/class/::: | 3 | 已支持 SysML requirement 基础块、`contains/copies/derives/satisfies/verifies/refines/traces` 关系、GraphIR 样式链路，以及 requirement / text / docRef 中的基础 markdown 保真渲染（强调/粗体/代码/链接文本） |
| architectureDiagram | ✅ | service、group、nested group、junction、edge with port、`{group}` boundary edge、icon、style/classDef/class/::: | 3 | 已支持 `architecture-beta` 官方语法主链路：嵌套 group、service/junction、端口侧边 `T/B/L/R` 连线、`{group}` 边界锚点、内置 icon 与 `logos:*` / `mdi:*` 等 iconify 名称透传，以及 GraphIR 样式链路 |
| c4 | ✅ | C4Context/Container/Component/Dynamic/Deployment、Person/System/Container/Component 家族、Boundary、Rel/BiRel/RelIndex/Rel_U/D/L/R/Back、AddElementTag、AddRelTag、UpdateElementStyle、UpdateRelStyle、`$tags`、`$link`、legend | 3 | 已支持 Mermaid C4 实验语法官方可用主链路：元素/边界嵌套、关系方向变体、`$tags` / `$link`、legend、`UpdateLayoutConfig`，以及 `RoundedBoxShape` / `EightSidedShape` / `DashedLine` / `DottedLine` / `BoldLine` helper；`sprite` 仍按 Mermaid 官方现状不纳入支持面 |
| block | ✅ | block、block-beta、columns、`space[:n]`、width span（`id:2`）、nested `block ... end`、常用形状、block arrow、边、style/classDef/class/::: | 3 | 已支持显式网格布局、空槽、嵌套 block、列跨度、`-->` / `---` 连线、带标签连接（含非引号文本）、`style/classDef/class/:::` 样式链路，以及圆角框/圆/双圆/菱形/六边形/圆柱/子程序/平行四边形/梯形/非对称块/block arrow 等官方高频形状 |
| packet-beta | 🟡 | `packet-beta`、`title`、bit range 字段（如 `0-15: "Source Port"`）、单 bit 字段（如 `106: "URG"`）、裸字段行 | 3+ | `MermaidPacketParser` 与 `MermaidPacketSubPipeline` 已落地；当前字段降到 `StructIR` 并复用 `StructLayout` 渲染为结构化字段列表，已接入 streaming session、`commonTest` 与 one-shot vs chunked 一致性校验。更接近官方 packet 表格/bit-grid 的二维视觉、字段跨度比例、颜色与分组仍待后续增强。 |

## 文档参考
- 官方：https://mermaid.js.org/intro/syntax-reference.html
- 黄金语料目录：`composeApp/src/commonTest/resources/mermaid/<diagram>/`

## Styling Notes
- Mermaid 支持引用外部 CSS class（如 `.cssClass > rect { ... }`）作为样式来源；本项目暂不支持外部 CSS，遇到此类用法将忽略并产出 `MERMAID-W010` 警告。
- Mermaid 颜色值支持：hex（`#RRGGBB` / `#RGB` / `#AARRGGBB`）、CSS 颜色关键字（如 `white` / `yellow`）、`rgb()/rgba()`、`hsl()/hsla()`；无法识别的颜色会被忽略并记录 `MERMAID-W011`。
- Mermaid 样式对齐方案详见 `docs/style-mermaid.md`。
