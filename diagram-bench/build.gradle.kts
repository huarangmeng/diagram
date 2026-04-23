plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(projects.diagramCore)
            implementation(projects.diagramLayout)
            implementation(projects.diagramParser)
            implementation(projects.diagramRender)
            implementation(libs.kotlinx.coroutinesCore)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

tasks.register<JavaExec>("runBench") {
    group = "verification"
    description = "Runs the streaming append/finish micro-benchmark and prints percentiles."
    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = jvmCompilation.output.allOutputs + jvmCompilation.runtimeDependencyFiles!!
    mainClass.set("com.hrm.diagram.bench.SessionBenchMainKt")
}
