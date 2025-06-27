# Sava Version Catalog [![Publish Release](https://github.com/sava-software/solana-version-catalog/actions/workflows/publish.yml/badge.svg)](https://github.com/sava-software/solana-version-catalog/actions/workflows/publish.yml)

Provides
a [Gradle Version Catalog](https://docs.gradle.org/current/userguide/version_catalogs.html#sec:importing-published-catalog)
and [Platform (BOM)](https://docs.gradle.org/current/userguide/platforms.html) for using Sava libraries.

```shell
./gradlew generateCatalogAsToml
```

### Gradle Configuration

#### settings.gradle.kts

```kotlin
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
```

#### gradle.properties

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

Add the following properties to `$HOME/.gradle/gradle.properties`.

```properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

### build.gradle.kts

```kotlin
plugins {
  id("org.gradlex.jvm-dependency-conflict-resolution")
}

jvmDependencyConflicts {
  consistentResolution {
    platform("software.sava:solana-version-catalog:${solanaBOMVersion}")
  }
}
```
