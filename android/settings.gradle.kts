// La version Android est un build a part : le plugin Android ne se melange pas aux
// modules Java du poste de travail. Elle reprend leurs sources telles quelles.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "breathless-android"
