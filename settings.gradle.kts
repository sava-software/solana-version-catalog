pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    if (
      providers.gradleProperty("savaGithubPackagesUsername").isPresent &&
      providers.gradleProperty("savaGithubPackagesPassword").isPresent
    ) {
      maven {
        name = "savaGithubPackages"
        url = uri("https://maven.pkg.github.com/sava-software/sava-build")
        credentials(PasswordCredentials::class)
      }
    }
//  includeBuild("../sava-build")
  }
}

plugins {
  id("software.sava.build") version "21.3.2"
}

rootProject.name = "solana-version-catalog"

include("solana-version-catalog")
