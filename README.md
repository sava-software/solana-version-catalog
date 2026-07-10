# Sava Version Catalog & BOM [![Publish Release GitHub Packages](https://github.com/sava-software/solana-version-catalog/actions/workflows/publish-gh.yml/badge.svg)](https://github.com/sava-software/solana-version-catalog/actions/workflows/publish-gh.yml) [![Maven Central](https://img.shields.io/maven-central/v/software.sava/solana-version-catalog)](https://central.sonatype.com/artifact/software.sava/solana-version-catalog)

A [Gradle Version Catalog](https://docs.gradle.org/current/userguide/version_catalogs.html#sec:importing-published-catalog)
and [Platform (BOM)](https://docs.gradle.org/current/userguide/platforms.html) providing a coherent, tested set of
versions for the [Sava](https://sava.software/) Solana Java libraries, the GLAM SDK, and the third-party dependencies
they build on — Jetty 12, gRPC & Protobuf, BouncyCastle, PostgreSQL & HikariCP, SLF4J, and JUnit.

Both views are published under a single set of coordinates:

```
software.sava:solana-version-catalog
```

The snippets below use `25.22.11` as a placeholder — check the Maven Central badge above or
the [releases page](https://github.com/sava-software/solana-version-catalog/releases) for the latest version.

## Repositories

Signed releases are published to [Maven Central](https://central.sonatype.com/artifact/software.sava/solana-version-catalog)
and [GitHub Packages](https://github.com/orgs/sava-software/packages). Note:

- The newest releases may appear on GitHub Packages before Maven Central.
- `software.sava:anchor-programs` is currently only published to GitHub Packages.

Maven Central needs no configuration. To also resolve from GitHub
Packages, [generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope and add it
to `$HOME/.gradle/gradle.properties`:

```properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

The repository definition shown below picks these up automatically via
its name, `savaGithubPackages`. The `sava-software/*` URL serves packages from
[every `sava-software` repository](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#installing-a-package).

## Using the Version Catalog

### settings.gradle.kts

```kotlin
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    // Only needed for artifacts or versions not yet on Maven Central:
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/*")
      credentials(PasswordCredentials::class)
    }
  }
  versionCatalogs {
    create("savaLibs") {
      from("software.sava:solana-version-catalog:25.22.11")
    }
  }
}
```

### build.gradle.kts

```kotlin
dependencies {
  implementation(savaLibs.sava.core)
  implementation(savaLibs.sava.rpc)
  implementation(savaLibs.bundles.glam)

  testImplementation(savaLibs.junit.jupiter)
}
```

### Bundles

| Bundle                                  | Contents                                                                                             |
|-----------------------------------------|------------------------------------------------------------------------------------------------------|
| `savaLibs.bundles.sava.solana.programs` | Core Solana stack: `sava-core`, `sava-rpc`, `idl-clients-bundle`, `idl-clients-spl`, `json-iterator`, BouncyCastle |
| `savaLibs.bundles.glam`                 | GLAM `sdk`, `services` and `ix-proxy`                                                                  |
| `savaLibs.bundles.jetty`                | Jetty 12 server with HTTP/2 & HTTP/3, ALPN and gzip compression                                        |
| `savaLibs.bundles.grpc.protobuf`        | `grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`, `protobuf-java`, `protoc-gen-grpc-java`             |

### gRPC & Protobuf code generation

The catalog also carries the [protobuf Gradle plugin](https://plugins.gradle.org/plugin/com.google.protobuf) and the
tool versions aligned with the library entries:

```kotlin
plugins {
  alias(savaLibs.plugins.google.protobuf.plugin)
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:${savaLibs.versions.protoc.get()}"
  }
  plugins {
    id("grpc") {
      artifact = "io.grpc:protoc-gen-grpc-java:${savaLibs.versions.grpc.get()}"
    }
  }
}
```

## Using the BOM (Platform)

The same coordinates can be used as a platform for version-less dependency declarations:

```kotlin
dependencies {
  implementation(platform("software.sava:solana-version-catalog:25.22.11"))

  implementation("software.sava:sava-core")
  implementation("software.sava:sava-rpc")
}
```

Or imported from Maven:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>software.sava</groupId>
      <artifactId>solana-version-catalog</artifactId>
      <version>25.22.11</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### Consistent resolution

To force every dependency the BOM manages to resolve to exactly the BOM's version across your whole build, use the
[GradleX JVM Dependency Conflict Resolution plugin](https://gradlex.org/jvm-dependency-conflict-resolution/):

```kotlin
plugins {
  id("org.gradlex.jvm-dependency-conflict-resolution") version "2.5"
}

jvmDependencyConflicts {
  consistentResolution {
    platform("software.sava:solana-version-catalog:25.22.11")
  }
}
```
