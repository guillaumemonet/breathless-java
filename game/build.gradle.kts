plugins {
    application
}

description = "Coquille jMonkeyEngine : fenetre, affichage du tampon et entrees clavier"

val jmeVersion = "3.7.0-stable"

dependencies {
    implementation(project(":gld-core"))
    implementation(project(":engine"))

    implementation("org.jmonkeyengine:jme3-core:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-desktop:$jmeVersion")
    runtimeOnly("org.jmonkeyengine:jme3-lwjgl3:$jmeVersion")
}

application {
    mainClass.set("fov.breathless.game.Main")
}

tasks.named<JavaExec>("run") {
    // Sans cela le jeu demarre dans game/ et ne trouve pas les donnees a cote du projet.
    workingDir = rootProject.projectDir
}

// --------------------------------------------------------------------------------------
// Le paquet autonome
//
// jpackage ne produit que pour le systeme qui le fait tourner : la tache construit donc
// le paquet de la machine courante. On s'en tient a l'« app-image », un repertoire qui
// porte son propre JRE et se lance sans rien installer — un vrai installeur demanderait
// WiX sous Windows.
//
// Le paquet ne contient pas les donnees du jeu : il va les chercher au premier lancement.
// --------------------------------------------------------------------------------------

val packageDir = layout.buildDirectory.dir("jpackage")
val runtimeDir = layout.buildDirectory.dir("jpackage-input")

/** Le systeme courant, sous la forme que LWJGL donne a ses jars de natifs. */
val nativesClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val name = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    when {
        arch == "amd64" || arch == "x86_64" -> name
        arch == "aarch64" -> "$name-arm64"
        arch.contains("86") -> if (name == "windows") "$name-x86" else name
        else -> "$name-$arch"
    }
}

/** Nom du systeme, tel qu'il apparait dans le nom de l'archive publiee. */
val platform: String = nativesClassifier + if (nativesClassifier.contains("-")) "" else "-x64"

/**
 * Rassemble le jar du jeu et ses dependances : c'est ce que jpackage prendra en entree.
 *
 * <p>LWJGL livre ses natifs pour tous les systemes ; un paquet ne sert que celui pour
 * lequel jpackage le construit. Garder les autres ajoutait huit megaoctets de code que
 * cette machine ne pourra jamais executer.
 */
val collectRuntime by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Rassemble le jar du jeu et ses dependances pour jpackage"
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath) {
        exclude { file ->
            val name = file.name
            name.contains("-natives-") && !name.endsWith("-natives-$nativesClassifier.jar")
        }
    }
    into(runtimeDir)
}

val jpackage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Construit le paquet autonome du jeu pour le systeme courant"
    dependsOn(collectRuntime)

    val out = packageDir.get().asFile
    val input = runtimeDir.get().asFile
    val mainJar = "game-$version.jar"

    doFirst {
        out.deleteRecursively()
        out.mkdirs()
        require(File(input, mainJar).isFile) { "jar principal introuvable : $mainJar" }
    }

    val tool = javaToolchains.launcherFor(java.toolchain).get()
            .metadata.installationPath.file("bin/jpackage").asFile.absolutePath

    commandLine(
        tool,
        "--type", "app-image",
        "--name", "Breathless",
        "--app-version", version,
        "--input", input.absolutePath,
        "--main-jar", mainJar,
        "--main-class", "fov.breathless.game.Main",
        "--dest", out.absolutePath,
        // Sans cette liste, jpackage embarque le JDK entier : cent cinquante megaoctets
        // pour un jeu qui en pese douze. Ne sont gardes que les modules dont le jeu se
        // sert vraiment — l'interface graphique, la journalisation de jMonkeyEngine, le
        // client HTTP qui va chercher les donnees, les preferences, et sun.misc.Unsafe
        // dont LWJGL a besoin.
        //
        // jdk.crypto.ec n'est pas facultatif : sans lui, la poignee de main TLS avec
        // GitHub echoue sur un handshake_failure, et le jeu ne peut plus aller chercher
        // ses donnees. Un module qu'aucun import ne trahit, et qui ne manque qu'a
        // l'execution.
        "--add-modules", listOf(
            "java.base", "java.desktop", "java.logging", "java.management",
            "java.net.http", "java.prefs", "java.xml",
            "jdk.crypto.ec", "jdk.unsupported",
        ).joinToString(","),
        "--vendor", "Portage freeware",
        "--description", "Breathless (Fields of Vision, 1995-96), porte en Java",
        "--copyright", "Freeware — voir la section « Origine et droits » du README",
    )
}

/** L'archive publiee : le paquet, tel quel, compresse. */
val packageZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Compresse le paquet autonome, pret a publier"
    dependsOn(jpackage)
    from(packageDir)
    archiveFileName.set("breathless-$version-$platform.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
