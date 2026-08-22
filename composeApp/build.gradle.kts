import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// GITHUB_SHA jest zawsze pełnym 40-znakowym SHA, nie krótkim jak `git rev-parse --short` —
// przycinamy ręcznie, żeby build w CI i lokalny dawały ten sam format do wyświetlenia w UI.
val gitShortSha = (System.getenv("GITHUB_SHA")?.take(7))
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().takeIf { it.isNotBlank() }
    }.getOrNull()
    ?: "dev"

// Wygenerowany plik zamiast BuildConfig: BuildConfig wymaga oddzielnej konfiguracji per target
// (androidLibrary/jvm), a to jest ten sam commit dla obu platform — wspólny plik w commonMain
// unika duplikacji i pozwala na ekranie potwierdzić, z jakiego commita realnie zbudowano apkę
// (przydatne przy diagnozowaniu, czy zainstalowany build faktycznie odpowiada danemu źródłu).
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/buildInfo")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("app/nuta/core/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package app.nuta.core

            object BuildInfo {
                const val VERSION_NAME = "0.1.0"
                const val GIT_SHA = "$gitShortSha"
            }
            """.trimIndent(),
        )
    }
}

kotlin {
    androidLibrary {
        namespace = "app.nuta.shared"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        androidResources { enable = true }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("app.nuta.MainKt")
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateBuildInfo.map { it.outputs.files.singleFile })
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)
                api(compose.components.resources)
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.coil.compose)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.webview)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.nuta.resources"
}

// Numer wersji rośnie z każdym buildem, żeby instalator Windows (MSI) zawsze widział
// nowszą wersję produktu — bez tego drugi instalator z tym samym numerem cichnie
// z błędem 1638 ("another version of this product is already installed") zamiast
// zaktualizować istniejącą instalację. GITHUB_RUN_NUMBER rośnie przy każdym uruchomieniu
// workflow i nie zależy od głębokości klonu; git rev-list liczy tylko commity widoczne
// w danym klonie, a actions/checkout robi domyślnie płytki klon (fetch-depth: 1) — tam
// zawsze zwracałoby 1, co dokładnie odtwarzałoby ten sam błąd 1638.
// runCatching, bo .dockerignore wyklucza .git — w buildzie w kontenerze nie ma repozytorium
// i git kończy się kodem 128; providers.exec rzuca wtedy wyjątkiem, więc bez tego elvis
// poniżej nigdy nie dostałby szansy i cały build padał zamiast użyć wartości domyślnej.
val gitCommitCount = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toIntOrNull()
    }.getOrNull()
    ?: 1

compose.desktop {
    application {
        mainClass = "app.nuta.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
            )
            packageName = "Nuta"
            packageVersion = "0.1.$gitCommitCount"
            description = "Nuta music player"
            vendor = "Nuta"
            modules("java.net.http")
            // SpotifyLoginHelper.exe (native/spotify-login) trafia tu w CI przed packageExe;
            // w runtime odczytywany przez SpotifyLoginHelperClient pod compose.application.resources.dir.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                // Diagnostic build: keep stderr/stdout visible when the bundled JVM fails.
                console = true
                menu = true
                shortcut = true
                iconFile.set(project.file("icons/nuta.ico"))
                // Stały upgrade code — pozwala MSI rozpoznać kolejne wersje jako aktualizację
                // tego samego produktu zamiast osobnej, kolidującej instalacji.
                upgradeUuid = "8defd09b-f5f2-49c0-b875-134107878223"
            }
        }
    }
}

afterEvaluate {
    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
    }
}
