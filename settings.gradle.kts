// BootForge — Universal Bootable Media Manager
// Arquivo de configuração do Gradle: lista todos os módulos do projeto.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BootForge"

// Núcleo em Kotlin puro (JVM): roda no Android, no PC e nos testes automatizados.
include(":core:blockdev")      // abstração de dispositivo de blocos (setores)
include(":core:integrity")     // SHA-256/512, verificação
include(":core:partition")     // MBR, GPT, detecção de sistema de arquivos
include(":core:fs-fat")        // leitor FAT12/16/32
include(":core:fs-exfat")      // formatador + leitor exFAT
include(":core:iso")           // leitor ISO9660/Joliet/El Torito/UDF (identificação de imagens)
include(":core:fs-ext")        // formatador ext2/ext3 (arquivos de persistência do Ventoy)
include(":feature:ventoy-config") // ventoy.json: esquema, validação, edição e backups
include(":feature:ventoy-core")// detecção, instalação e atualização do Ventoy

// Android
include(":android:usb")        // USB OTG (USB Host API + Mass Storage/SCSI)
include(":app")                // aplicativo (Jetpack Compose)

// Ferramentas de desenvolvimento/teste
include(":tools:cli")          // linha de comando para testar em arquivos de imagem
