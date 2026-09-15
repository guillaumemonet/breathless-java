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
