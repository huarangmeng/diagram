import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.mavenPublish)
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
    @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }

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

mavenPublishing {
    publishToMavenCentral(true)

    signAllPublications()

    coordinates("io.github.huarangmeng", "diagram-render", rootProject.property("VERSION").toString())

    pom {
        name.set("Diagram Render")
        description.set("Compose Multiplatform rendering facade and streaming session APIs for Diagram.")
        inceptionYear.set("2026")
        url.set("https://github.com/huarangmeng/diagram")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("huarangmeng")
                name.set("huarangmeng")
                url.set("https://github.com/huarangmeng/")
            }
        }
        scm {
            url.set("https://github.com/huarangmeng/diagram")
            connection.set("scm:git:git://github.com/huarangmeng/diagram.git")
            developerConnection.set("scm:git:ssh://git@github.com/huarangmeng/diagram.git")
        }
    }
}
