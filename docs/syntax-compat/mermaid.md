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
| journey | ⬜ | section、task with score | 2 | |
| mindmap | ✅ | 缩进树、默认节点、`[]`/`()`/`(( ))`/`)) ((`、`) (`、`{{ }}`、`::icon(...)` | 2 | 已支持 bang / cloud 与双侧布局；`DrawIcon` 会输出结构化图标命令，未集成宿主 icon font 时以回退字样显示；`:::` class 行仍忽略并记 warning |
| xyChart | ✅ | `xychart`/`xychart-beta`、line/bar/scatter/area、x/yAxis、title、horizontal、frontmatter `showDataLabel` / `themeVariables` | 2 | 当前支持分类 x 轴和数值范围 x 轴；log/time axis 仍未实现 |
| quadrantChart | ✅ | title、x/y-axis、quadrant-1..4、points、点内联样式、frontmatter `themeVariables` | 2 | 支持 `color/radius/stroke-color/stroke-width`；`classDef` / `:::class` 当前忽略并记 warning |
| sankey | ⬜ | source,target,value | 2 | |
| kanban | ✅ | column、card、`@{ assigned/ticket/priority }` metadata、frontmatter `ticketBaseUrl` | 2 | `priority` 已做 badge 化展示；`ticketBaseUrl` 会在渲染阶段生成外链命令 |
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
- Mermaid 颜色值支持：hex（`#RRGGBB` / `#RGB` / `#AARRGGBB`）、CSS 颜色关键字（如 `white` / `yellow`）、`rgb()/rgba()`、`hsl()/hsla()`；无法识别的颜色会被忽略并记录 `MERMAID-W011`。
- Mermaid 样式对齐方案详见 `docs/style-mermaid.md`。
