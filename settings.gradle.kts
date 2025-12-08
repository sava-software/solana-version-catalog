pluginManagement {
  repositories {
    gradlePluginPortal()
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      credentials(PasswordCredentials::class)
    }
  }
//  includeBuild("../sava-build")
}

plugins {
  id("software.sava.build") version "0.3.0"
}

rootProject.name = "solana-version-catalog"

include("solana-version-catalog")
