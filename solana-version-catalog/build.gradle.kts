// This setup publishes the defined versions as BOM (platform) and Version Catalog
// It follows the approach of https://repo1.maven.org/maven2/io/micronaut/platform/micronaut-platform

plugins {
  id("java-platform")
  id("version-catalog")
  id("software.sava.build.feature.publish")
}

group = "software.sava"
version = providers.gradleProperty("version").getOrElse("")

// Tests

// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-params
val junit = "5.13.4"

// Compile & Implementation

// https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk18on
// https://www.bouncycastle.org/download/bouncy-castle-java/#latest
val bouncyCastle = "1.81"

// https://github.com/sava-software/json-iterator
val savaJsonIterator = "21.0.11"
val sava = "21.0.1"
val savaPrograms = "21.0.1"

dependencies.constraints {
  // Tests
  api("org.junit.jupiter:junit-jupiter:$junit")
  api("org.junit.jupiter:junit-jupiter-api:$junit")
  api("org.junit.jupiter:junit-jupiter-params:${junit}")

  // Compile & Implementation

  api("org.bouncycastle:bcprov-jdk18on:${bouncyCastle}")

  api("software.sava:json-iterator:$savaJsonIterator")

  api("software.sava:sava-core:${sava}")
  api("software.sava:sava-rpc:${sava}")

  api("software.sava:solana-programs:${savaPrograms}")
}

catalog {
  // Library entries are derived from the BOM entries. The alias corresponds to the 'name' by default.
  // The cases where the alias should differ are defined below.
  configureExplicitAlias("bouncycastle", "org.bouncycastle", "bcprov-jdk18on")
  configurations.api.get().dependencyConstraints.forEach { constraint ->
    if (constraint.group == "software.sava" && !constraint.name.startsWith("sava")) {
      configureExplicitAlias("sava-${constraint.name}", constraint.group, constraint.name)
    }
  }

  versionCatalog {
    // Bundles
    bundle(
      "sava-solana-programs", listOf(
        "bouncycastle",
        "sava-json-iterator",
        "sava-core",
        "sava-rpc",
        "sava-solana-programs"
      )
    )
  }
}
