plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.jmdns:jmdns:3.5.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("dev.atvremote.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
