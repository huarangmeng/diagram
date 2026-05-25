# 发布与版本

## 1. 版本号
- 语义化版本 `MAJOR.MINOR.PATCH`。
- `0.x` 阶段（Phase 0-3）：minor 可破坏 API。
- `1.0` 在 Phase 7 完成、文档与导出全覆盖后发布。
- 当前仓库版本源于根目录 `gradle.properties` 的 `VERSION` 字段；发布前先更新该值。

## 2. 工件
| Maven 坐标 | 模块 |
|---|---|
| `io.github.huarangmeng:diagram-core` | `:diagram-core`（含 SVG / PNG / JPEG 导出） |
| `io.github.huarangmeng:diagram-layout` | `:diagram-layout` |
| `io.github.huarangmeng:diagram-parser` | `:diagram-parser`（Mermaid / PlantUML / DOT 三合一，按子包隔离） |
| `io.github.huarangmeng:diagram-render` | `:diagram-render`（Compose 渲染 + 顶层门面） |

## 3. 发布平台
- Maven Central（JVM/Android/Multiplatform 元数据）。
- npm（按需，仅当未来需要把 wasm 工件直接给 web 项目时）。

## 4. 变更日志
- 维护根目录 `CHANGELOG.md`（Phase 7 创建），按 Keep a Changelog 风格。

## 5. 流程
1. 通过所有 `./gradlew allTests`。
2. 更新 `gradle.properties` 中的 `VERSION`。
3. 确认 GitHub 仓库 Secrets 已配置：`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`、`SIGNING_KEY_ID`、`SIGNING_PASSWORD`、`GPG_KEY_CONTENTS`。
4. 本地可先执行 `./gradlew publishToMavenLocal --no-configuration-cache` 验证四个 SDK 模块的 publication。
5. 创建 GitHub Release（`released` / `prereleased`），触发 `.github/workflows/publish.yml` 执行 `publishToMavenCentral`。
