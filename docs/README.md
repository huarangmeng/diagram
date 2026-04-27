# docs/

项目文档总目录。开发者/agent 在动手前先读 `../AGENTS.md`，再按需进入下面文档。

## 总览
- [`plan.md`](./plan.md) —— 8 阶段路线图、模块切分、布局算法清单、风险表
- [`architecture.md`](./architecture.md) —— 顶层架构、数据流、模块边界、扩展点
- [`api.md`](./api.md) —— 公开 API 契约（`Diagram.parse` / `DiagramView` / 导出）
- [`rules.md`](./rules.md) —— **硬规则手册**（红线 / 严重等级 / 未来自动化钩子）

## 设计规范
- [`ir.md`](./ir.md) —— 通用 IR（`DiagramModel` 家族）字段语义
- [`draw-command.md`](./draw-command.md) —— 渲染中间层指令集，Compose / SVG / PNG 共用
- [`theme.md`](./theme.md) —— 主题 / 颜色 / 字体 / 形状 / 箭头规范
- [`style-mermaid.md`](./style-mermaid.md) —— Mermaid 样式系统（对齐 Mermaid.js：themeVariables + classDef/style/linkStyle）
- [`coordinate-system.md`](./coordinate-system.md) —— 坐标、单位、DPI、文本基线约定

## 布局算法
- [`layout/README.md`](./layout/README.md) —— 布局引擎接口与算法目录
- 各算法专题文档将在 Phase 推进时新增（Sugiyama / 力导向 / 正交 / 树式 / 时间轴 / 桑基 / …）

## 语法兼容矩阵（每个 Phase 实现一项就更新一项）
- [`syntax-compat/mermaid.md`](./syntax-compat/mermaid.md)
- [`syntax-compat/plantuml.md`](./syntax-compat/plantuml.md)
- [`syntax-compat/dot.md`](./syntax-compat/dot.md)

## 工程化
- [`testing.md`](./testing.md) —— 黄金语料、快照策略、跨平台测试
- [`contributing.md`](./contributing.md) —— 新增图类型 / 新增语法 / 新增布局算法的步骤
- [`release.md`](./release.md) —— 版本号、Maven 发布、变更日志规范
- [`adr/`](./adr/) —— Architecture Decision Record（重要决策记一条）

> 文档新增/迁移规则：动手时同步更新本 README 索引和 `../AGENTS.md` §3。
