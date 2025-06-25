pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "savaGithubPackages"
            url = uri("https://maven.pkg.github.com/sava-software/sava-build")
            credentials(PasswordCredentials::class)
        }
    }
}

plugins {
    id("software.sava.build") version "0.1.13"
}

rootProject.name = "solana-version-catalog"

include("solana-version-catalog")
