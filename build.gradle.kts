plugins {
    java
}

allprojects {
    version = "0.2.2"
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
        // Le chemin est resolu depuis la racine du projet, et non depuis le repertoire du
        // module : sans cela un chemin relatif ne designe rien pour les tests, qui se
        // contentent alors de s'ignorer — une compilation toute verte sans avoir rien
        // verifie.
        System.getProperty("breathless.data")?.let {
            systemProperty("breathless.data", rootProject.file(it).absolutePath)
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
