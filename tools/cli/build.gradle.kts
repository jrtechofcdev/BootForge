// Ferramenta de linha de comando do BootForge (PC: Windows/Linux/macOS com Java 17+).
// Usa exatamente o mesmo núcleo do app Android — serve para testar em arquivos de imagem.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":feature:ventoy-core"))
    implementation(project(":feature:iso-writer"))
}

application {
    mainClass.set("dev.bootforge.cli.MainKt")
    applicationName = "bootforge-cli"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
