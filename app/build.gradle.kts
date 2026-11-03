plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.voicekeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.voicekeyboard"
        minSdk = 29
        targetSdk = 36
        versionCode = 39
        versionName = "0.1.38"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // clearPackageData bewusst false: Ein Instrumented-Lauf darf einen echten
        // Modellbestand (671 MB in filesDir/models) nicht vernichten; die Tests
        // zielen auf eigene Ziele (RecordingSink/Fake-InputConnection) und
        // brauchen kein geleertes Paket.
        testInstrumentationRunnerArguments["clearPackageData"] = "false"

        ndk {
            // V10: Release nur arm64 (Geraete); Debug behaelt x86_64 (Emulator).
            // V15: -Parm64Only schraenkt JEDENVARIANT auf arm64 ein (Mess-APK
            // fuer den Nutzer, ~75 statt ~123 MB); ohne Property ist das
            // Standardverhalten unveraendert (Debug: beide ABIs, Emulator bleibt testbar).
            val arm64Only = project.hasProperty("arm64Only")
            if (arm64Only || project.gradle.startParameter.taskNames.any {
                    it.contains("Release") && !it.contains("AndroidTest")
                }) {
                abiFilters += listOf("arm64-v8a")
            } else {
                abiFilters += listOf("x86_64", "arm64-v8a")
            }
        }
    }


    signingConfigs {
        create("release") {
            // Credentials aus Projekt-Properties (local.properties) mit Env-Fallback —
            // KEINE Klartext-Passwörter in dieser Datei.
            val vkStorePass = (project.findProperty("vk.storePassword") as? String)
                ?: System.getenv("VK_STORE_PASSWORD")
            val vkKeyPass = (project.findProperty("vk.keyPassword") as? String)
                ?: System.getenv("VK_KEY_PASSWORD")
            val vkKeyAlias = (project.findProperty("vk.keyAlias") as? String)
                ?: System.getenv("VK_KEY_ALIAS") ?: "voicekeyboard"
            if (vkStorePass.isNullOrBlank() || vkKeyPass.isNullOrBlank()) {
                throw GradleException(
                    "Release-Signatur benötigt vk.storePassword/vk.keyPassword " +
                        "(local.properties) oder VK_STORE_PASSWORD/VK_KEY_PASSWORD (Env)"
                )
            }
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = vkStorePass
            keyAlias = vkKeyAlias
            keyPassword = vkKeyPass
        }
    }

    buildTypes {
        // Release-Signatur auch für Debug-Builds: End-to-End-Tests auf einem Gerät
        // müssen ohne Signaturkonflikt (INSTALL_FAILED_UPDATE_INCOMPATIBLE) neben
        // der Release-APK installierbar sein; die Distribution bleibt unverändert.
        // applicationIdSuffix bleibt unangetastet (IME-ID/Testpfade hängen daran).
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            // v1 bewusst OHNE R8/Resource-Shrinking: die ONNX-Runtime bindet per JNI
            // und die Modell-/Layout-Assets sind reine Ressourcen — Keep-Regeln dafür
            // sind nicht reviewed. Aktivierung erst nach Review (IMPLEMENTATION_PLAN M8).
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Offline-Container: lintVital (läuft automatisch bei Release-Builds) kann
    // lint-gradle nicht aus dem Cache resolven — der vollständige lint-Lauf ist
    // Teil von M8 und wird dort mit Netz/Cache ausgeführt. Build nur deaktiviert,
    // NICHT die lint-Konfiguration selbst.
    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // V3: LiteRT 2.3.2 ist mit Kotlin 2.4-Metadaten kompiliert; das Projekt
        // nutzt Kotlin 2.0 — die Language-Version 2.0 kann neuere Metadaten lesen
        // (nur die Sprache bleibt 2.0, kein Verhaltensunterschied im eigenen Code).
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}

// Offline-Fix: AGP 8.8.2 fordert UTP-Plugins in Version 31.8.2, im lokalen Gradle-Cache
// liegt nur 31.7.3 (Netzzugriff verboten). Substitution auf die vorhandene Version —
// nur für den connectedAndroidTest-Lauf, keine Auswirkung auf App/Dependencies.
configurations.all {
    resolutionStrategy.dependencySubstitution.all {
        val req = requested
        if (req is org.gradle.api.artifacts.component.ModuleComponentSelector && req.group == "com.android.tools.utp") {
            useTarget("com.android.tools.utp:${req.module}:31.7.3", "offline: 31.8.2 nicht im Cache")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // V3/V13: LiteRT nur noch im DEBUG-Build (Variante B qualitätsgesteuert aus
    // Release entfernt — Quellcode bleibt im Debug-Source-Set, Messtür offen)
    debugImplementation("com.google.ai.edge.litert:litert:2.3.2")

    // ONNX Runtime — gleiche Version wie die Python-Referenz (onnxruntime 1.31.1,
    // ASR_CONTRACT.md Deckblatt), damit die Inferenz reproduzierbar ist.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")

    // Instrumented Tests — Versionen aus dem lokalen Gradle-Cache (offline-fähig)
    // Offline-Fix: versionedparcelable:1.1.1 (transitiv via core-ktx) verlangt
    // androidx.collection:1.0.0 — offline nicht im Cache; 1.1.0 ist kompatibel und vorhanden.
    androidTestImplementation("androidx.collection:collection:1.1.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
