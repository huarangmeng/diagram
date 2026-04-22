# 发布与版本

## 1. 版本号
- 语义化版本 `MAJOR.MINOR.PATCH`。
- `0.x` 阶段（Phase 0-3）：minor 可破坏 API。
- `1.0` 在 Phase 7 完成、文档与导出全覆盖后发布。

## 2. 工件
| Maven 坐标（计划） | 模块 |
|---|---|
| `com.hrm.diagram:diagram-core` | `:diagram-core`（含 SVG / PNG / JPEG 导出） |
| `com.hrm.diagram:diagram-layout` | `:diagram-layout` |
| `com.hrm.diagram:diagram-parser` | `:diagram-parser`（Mermaid / PlantUML / DOT 三合一，按子包隔离） |
| `com.hrm.diagram:diagram-render` | `:diagram-render`（Compose 渲染 + 顶层门面） |
| `com.hrm.diagram:diagram` | 聚合 BOM/POM，依赖以上全部 |

## 3. 发布平台
- Maven Central（JVM/Android/Multiplatform 元数据）。
- npm（按需，仅当未来需要把 wasm 工件直接给 web 项目时）。

## 4. 变更日志
- 维护根目录 `CHANGELOG.md`（Phase 7 创建），按 Keep a Changelog 风格。

## 5. 流程
1. 通过所有 `./gradlew allTests`。
2. Bump 版本号（`gradle/libs.versions.toml`）。
3. 生成 changelog 条目。
4. tag `v<version>`，CI 触发发布。
