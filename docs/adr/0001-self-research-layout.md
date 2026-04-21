# 0001: 完全自研布局算法

- 状态：Accepted
- 日期：2026-04
- 关联：`plan.md` §2、§5

## 背景
图表渲染最难的部分是自动布局。市场上有成熟方案：
- ELK（Java，JVM 友好）
- dagre / cytoscape.js（JS）
- Graphviz 原生 C 库

但我们要 KMP（Android/iOS/JVM/JS/Wasm）一致性，且不希望被 JS 互操作 / JVM 专属库绑死。

## 选项
- A：包装现有 JS/Java 库（多平台行为不一致、Wasm 体积、iOS 麻烦）。
- B：完全自研纯 Kotlin 布局算法（工程量大但控制力强）。
- C：混合：简单图自研，复杂图接 ELK（架构复杂）。

## 决定
选 B：完全自研，纯 commonMain 实现。

## 影响
- Phase 1/3/4 必须分别投入实现 Sugiyama、力导向、正交+A* 路由。
- 每个算法专题文档落在 `docs/layout/`。
- 风险：质量短期内可能不如原生工具；用 `LayoutHint` 给用户兜底。
