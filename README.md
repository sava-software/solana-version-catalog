# Sava Version Catalog

Provides a set of library versions that work together in the same build.

## Usage

This covers how to add the version catalog to your build. For information on how to reference the dependencies see the
official [Gradle version catalog docs](https://docs.gradle.org/current/userguide/version_catalogs.html).

### Local

Generate a `libs.versions.toml` file which will be located in build/version-catalog.

This may be placed in your local gradle directory.

```shell
./gradlew generateCatalogAsToml
```

### Remote Maven Repository

Add to you your `settings.gradle` file and configure the username, password and version.

```groovy
dependencyResolutionManagement {
  repositories {
    maven {
      name = "GithubPackages"
      url = "https://maven.pkg.github.com/sava-software/solana-version-catalog"
      credentials {
        username = GITHUB_ACTOR
        password = GITHUB_TOKEN
      }
    }
  }
  versionCatalogs {
    libs {
      from("software.sava:solana-version-catalog:<VERSION>")
    }
  }
}
```
