plugins {
    application
}

description = "Outils en ligne de commande : inspection et conversion des donnees Breathless"

dependencies {
    implementation(project(":gld-core"))
    implementation(project(":engine"))
}

application {
    mainClass.set("fov.breathless.gld.cli.Main")
}

// « gradle run » depuis la racine vise la tache de meme nom dans chaque module. Sans
// argument l'outil affiche son aide et sort en erreur, ce qui faisait echouer la
// commande apres la fermeture du jeu. On le retire de cette selection et on expose
// l'outil sous un nom a lui.
tasks.named<JavaExec>("run") {
    enabled = false
}

tasks.register<JavaExec>("cli") {
    group = "application"
    description = "Lance les outils : gradle cli --args=\"probe Breathless\""
    mainClass.set("fov.breathless.gld.cli.Main")
    classpath = sourceSets["main"].runtimeClasspath
    // Les donnees se trouvent a cote du projet, pas sous gld-cli/.
    workingDir = rootProject.projectDir
}
