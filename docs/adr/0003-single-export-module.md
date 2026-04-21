# 0003: 单一 :diagram-export 模块容纳 SVG / PNG / JPEG

- 状态：Accepted
- 日期：2026-04
- 关联：用户反馈 + `plan.md` §2、§7

## 背景
原计划拆 `:diagram-export-svg`（commonMain）和 `:diagram-export-image`（expect/actual）两个模块。

## 选项
- A：拆成两个模块（依赖更细，但导出场景几乎总是同时需要）。
- B：合并为一个 `:diagram-export`，内部按文件区分 commonMain / 平台源集。

## 决定
选 B。

## 影响
- Gradle 模块数量减少；用户依赖更简单（一个工件搞定全部导出）。
- SVG 与 PNG/JPEG 共享 `DrawCommand → PlatformCanvas` 适配器，路径更短。
- 不利点：用户即使只想要 SVG 也会拉进 expect/actual 平台代码（实际几乎可忽略）。
