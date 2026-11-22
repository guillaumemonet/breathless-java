plugins {
    id("com.android.application") version "9.5.2"
}

val jmeVersion = "3.7.0-stable"

/** La version du jeu, celle du build principal. */
val gameVersion: String = run {
    val text = file("../build.gradle.kts").readText()
    Regex("version = \"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "0.0.0"
}

// Les sources du moteur et de la coquille, sans ce qui ne sert qu'au poste de travail :
// le lancement en ligne de commande, et les boites de dialogue Swing.
val sharedSources = layout.buildDirectory.dir("shared-src")
val copySharedSources = tasks.register<Sync>("copySharedSources") {
    from("../gld-core/src/main/java")
    from("../engine/src/main/java")
    from("../game/src/main/java") {
        exclude("**/game/Main.java", "**/game/FirstRun.java", "**/game/DesktopGpu.java")
    }
    into(sharedSources)
}

android {
    namespace = "fov.breathless.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "fov.breathless"
        minSdk = 34
        targetSdk = 36
        versionName = gameVersion
        versionCode = gameVersion.split(".").fold(0) { acc, part -> acc * 100 + part.toInt() }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.directories.add(sharedSources.get().asFile.path)
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Signe avec la cle de debogage : l'APK s'installe tel quel, hors boutique.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += listOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copySharedSources)
}

dependencies {
    implementation("org.jmonkeyengine:jme3-core:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-android:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-android-native:$jmeVersion")
}


