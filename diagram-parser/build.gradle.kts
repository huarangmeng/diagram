import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.hrm.diagram.parser"
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
            implementation(projects.diagramCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
