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
| MERMAID-W001 | 节点未声明，自动创建占位 | 任意图 |
| PLANTUML-E001 | 缺失 `@enduml` 闭合 | block parser |
| PLANTUML-W001 | 未识别 `skinparam`，已忽略 | lowering |
| DOT-E001 | 词法错误：未闭合字符串 | lexer |
| DOT-W001 | 不支持的属性已忽略 | attrs lowering |
| LAYOUT-W001 | 节点过多，启用降级布局 | layout engine |
| RENDER-W001 | 字体回退 | renderer |
| EXPORT-W001 | 部分指令栅格化时降级 | export 平台层 |

> 新增码必须 PR 中同步更新本文件。
