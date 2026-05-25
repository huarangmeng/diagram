import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
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

mavenPublishing {
    publishToMavenCentral(true)

    signAllPublications()

    coordinates("io.github.huarangmeng", "diagram-parser", rootProject.property("VERSION").toString())

    pom {
        name.set("Diagram Parser")
        description.set("Streaming parsers and lowering pipeline for Mermaid, PlantUML, and Graphviz DOT.")
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
