plugins {
    java
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Permet de pointer les donnees d'origine : gradle test -Dbreathless.data=<chemin>
        System.getProperty("breathless.data")?.let { systemProperty("breathless.data", it) }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
