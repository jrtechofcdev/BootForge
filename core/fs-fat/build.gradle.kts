// Módulo Kotlin/JVM puro: sem dependência do Android, testável no PC.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Bytecode Java 17: compatível com Android (D8) e com o JDK do PC.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:blockdev"))
    testImplementation(libs.xz)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Caminho do pacote original do Ventoy (usado por testes que validam com os arquivos reais).
    systemProperty("bootforge.ventoyDir", rootProject.file("third_party/ventoy-1.1.18").absolutePath)
    maxHeapSize = "1g"
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}
