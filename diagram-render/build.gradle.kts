import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.hrm.diagram.render"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}
    }

    jvm { }
    iosArm64()
    iosSimulatorArm64()
    js { browser(); nodejs() }
    @OptIn(ExperimentalWasmDsl::class) wasmJs { browser(); nodejs() }

    sourceSets {
        commonMain.dependencies {
            api(projects.diagramCore)
            api(projects.diagramLayout)
            api(projects.diagramParser)
            api(libs.kotlinx.coroutinesCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
