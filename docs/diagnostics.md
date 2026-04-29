# 诊断码（Diagnostics）

格式：`<LANG>-<E|W|I><三位数>`。

- `LANG`：`MERMAID` / `PLANTUML` / `DOT` / `LAYOUT` / `RENDER` / `EXPORT`。
- `E` = Error（无法构建 IR），`W` = Warning（可继续），`I` = Info。

## 通用规则
- 每个诊断条目必须带 `Span`（除非完全无法定位）。
- message 双语优先：英文为主，中文括注（在 Phase 7 接入 i18n 前）。
- 同一语义不要分散多个码；先复用，再新增。

## 已分配（实现时按需追加）

| 码 | 含义 | 出现位置 |
|---|---|---|
| MERMAID-E001 | 未知首行图类型关键字 | dispatcher |
| MERMAID-E002 | flowchart 边语法不匹配 | flowchart parser |
| MERMAID-E003 | erDiagram 关系语法不匹配 | erDiagram parser |
| MERMAID-E004 | erDiagram 实体属性语法不匹配 | erDiagram parser |
| MERMAID-E200 | pie 语法不匹配（title / slice 行） | pie parser |
| MERMAID-E201 | gauge 语法不匹配（title / min/max/value 行） | gauge parser |
| MERMAID-E202 | timeline 语法不匹配（title / section / event 行） | timeline parser |
| MERMAID-E203 | gantt 语法不匹配（title / dateFormat / section / task 行） | gantt parser |
| MERMAID-E204 | mindmap 语法不匹配（缩进 / 节点行） | mindmap parser |
| MERMAID-E205 | kanban 语法不匹配（column / card / metadata 行） | kanban parser |
| MERMAID-E206 | xyChart 语法不匹配（axis / series 行） | xyChart parser |
| MERMAID-E207 | quadrantChart 语法不匹配（axis / quadrant / point 行） | quadrantChart parser |
| MERMAID-E208 | journey 语法不匹配（title / section / step 行） | journey parser |
| MERMAID-E209 | sankey 语法不匹配（source,target,value 行） | sankey parser |
| MERMAID-E210 | gitGraph 语法不匹配（commit / branch / checkout / merge / cherry-pick 行） | gitGraph parser |
| MERMAID-E211 | requirementDiagram 语法不匹配（block / property / relation 行） | requirementDiagram parser |
| MERMAID-E212 | architectureDiagram 语法不匹配（group / service / junction / edge 行） | architectureDiagram parser |
| MERMAID-E213 | c4Diagram 语法不匹配（header / element / boundary / relation / update style 行） | c4 parser |
| MERMAID-E214 | block 图语法不匹配（block-beta / columns / row item / block / edge 行） | block parser |
| MERMAID-W001 | 节点未声明，自动创建占位 | 任意图 |
| MERMAID-W010 | 外部 CSS class 已忽略（不支持） | mermaid style resolver / parser |
| MERMAID-W011 | 无法识别的颜色值已忽略（支持 CSS 颜色关键字、`rgb/rgba`、`hsl/hsla`、以及 hex） | mermaid themeVariables / style parser |
| MERMAID-W012 | 不支持的样式 key 已忽略 | mermaid style parser |
| PLANTUML-E001 | 缺失 `@enduml` 闭合 | block parser |
| PLANTUML-W001 | 未识别 `skinparam`，已忽略 | lowering |
| DOT-E001 | 词法错误：未闭合字符串 | lexer |
| DOT-W001 | 不支持的属性已忽略 | attrs lowering |
| LAYOUT-W001 | 节点过多，启用降级布局 | layout engine |
| RENDER-W001 | 字体回退 | renderer |
| EXPORT-W001 | 部分指令栅格化时降级 | export 平台层 |

> 新增码必须 PR 中同步更新本文件。
