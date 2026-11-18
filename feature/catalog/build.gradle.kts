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
    api(project(":core:integrity"))
    api(project(":core:blockdev"))
    api(libs.kotlinx.serialization.json)
    // OpenPGP: verificação das assinaturas oficiais dos arquivos de checksum (licença MIT)
    implementation(libs.bouncycastle.pg)
    implementation(libs.bouncycastle.prov)
    testImplementation(kotlin("test"))
}

tasks.test {
    // Nomes de arquivo com acentos nos testes (o ambiente pode estar em locale ASCII)
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
    useJUnitPlatform()
    // Caminho do pacote original do Ventoy (usado por testes que validam com os arquivos reais).
    systemProperty("bootforge.ventoyDir", rootProject.file("third_party/ventoy-1.1.18").absolutePath)
    maxHeapSize = "1g"
    // Teste opcional contra as fontes reais (BOOTFORGE_LIVE=1); declarado como entrada para o cache.
    val live = System.getenv("BOOTFORGE_LIVE") ?: ""
    inputs.property("live", live)
    environment("BOOTFORGE_LIVE", live)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}
