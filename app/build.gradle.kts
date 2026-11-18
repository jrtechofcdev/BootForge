import java.io.File

// Aplicativo Android BootForge (Jetpack Compose).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Renderiza as telas Compose na JVM (capturas de tela sem emulador).
    alias(libs.plugins.paparazzi)
}

/**
 * Copia os componentes ORIGINAIS do Ventoy usados na instalação para os assets do APK.
 * Fonte: third_party/ventoy-1.1.18 (pacote oficial intacto). Os arquivos não são alterados.
 */
abstract class PrepareVentoyAssets : DefaultTask() {
    @get:InputDirectory
    abstract val ventoyDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        listOf("boot/boot.img", "boot/core.img.xz", "ventoy/ventoy.disk.img.xz", "ventoy/version").forEach { rel ->
            val src = ventoyDir.get().file(rel).asFile
            check(src.isFile) { "Arquivo do Ventoy ausente: $src" }
            val dst = File(out, "ventoy/$rel")
            dst.parentFile.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
        // Tema original e exemplos de plugin (para "Instalar tema original" e referência)
        ventoyDir.get().dir("plugin").asFile.copyRecursively(File(out, "ventoy/plugin"), overwrite = true)
    }
}

val prepareVentoyAssets = tasks.register<PrepareVentoyAssets>("prepareVentoyAssets") {
    ventoyDir.set(rootProject.layout.projectDirectory.dir("third_party/ventoy-1.1.18"))
    outputDir.set(layout.buildDirectory.dir("generated/ventoyAssets"))
}

android {
    namespace = "dev.bootforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.bootforge.app"
        minSdk = 26          // Android 8.0
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            // R8 remove o código não usado (ícones, etc.) e deixa o APK bem menor.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Assinado com a chave de debug APENAS para testes; publicação exigirá chave própria.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareVentoyAssets, PrepareVentoyAssets::outputDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":android:usb"))
    implementation(project(":feature:ventoy-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("bootforge.ventoyDir", rootProject.file("third_party/ventoy-1.1.18").absolutePath)
}
