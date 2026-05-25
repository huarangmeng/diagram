# =============================================================================
# diagram-parser 消费方保留规则（Consumer ProGuard Rules）
#
# 该文件随 AAR 一起发布，会在使用本库的 Android 应用进行 R8/ProGuard 优化时自动生效。
#
# 编写原则（参考 Android 官方《库作者优化指南》）：
#  - 仅保留运行时反射 / JNI / 序列化等无法被 R8 静态分析到的入口点。
#  - 严禁包级 -keep（如 -keep com.hrm.diagram.parser.** 会导致下游 App 体积膨胀）。
#  - 严禁全局选项（如 -dontobfuscate / -allowaccessmodification / -ignorewarnings）。
# =============================================================================
#
# diagram-parser 不使用反射、JNI、ServiceLoader 与运行时注解处理，
# 解析器 / lowering / streaming session 均通过纯 Kotlin 静态调用完成，R8 可完整追踪。
# 因此不需要任何 -keep 规则。
#
# 仅声明对类元数据属性的依赖，避免下游 App 自定义规则误剥离这些属性。
#
# Kotlin sealed class / data class / 协程 suspend 等所需的元数据
-keepattributes InnerClasses,EnclosingMethod,Signature
#
# 公共 API 注解
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
#
# -----------------------------------------------------------------------------
# Compose Desktop / 普通 JVM ProGuard 兼容（仅压制 fatal warning，不阻止 shrink）
# -----------------------------------------------------------------------------
# 背景：Kotlin 编译器在处理 `companion object` 中 `private inline fun` + `private val`
# 组合时，会把私有字段 lift 到 outer 类的 synthetic 静态字段并生成桥接访问器；
# 在某些 Kotlin/KMP 版本组合下，`*$Companion.class` 仍按原名字面引用 outer 类的
# 字段，ProGuard 全图静态校验时会判定为 unresolved 并把 warning 升级为 fatal。
# JVM 运行时按惰性解析处理，且实际调用常被外层内联，运行期不一定触发该路径。
#
# 处理方式：仅压制对应 warning，避免 fatal；不要使用 `-keep`，让 R8/ProGuard
# 继续按正常规则 shrink 与 obfuscate 本库代码，避免下游应用体积膨胀。
-dontwarn com.hrm.diagram.**
