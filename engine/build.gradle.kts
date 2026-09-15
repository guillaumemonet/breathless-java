plugins {
    `java-library`
}

description = "Moteur de rendu : raycaster et rasterisation logicielle, sans dependance graphique"

dependencies {
    // Le moteur expose des types de gld-core (Level, Texture, Palette) dans sa propre
    // API : les consommateurs doivent les voir.
    api(project(":gld-core"))
}

tasks.withType<Test>().configureEach {
    // Les traces des tests restent muettes par defaut : gradle test -Dbreathless.verbose=1
    testLogging.showStandardStreams = System.getProperty("breathless.verbose") != null
}
