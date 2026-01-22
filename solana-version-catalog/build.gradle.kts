// This setup publishes the defined versions as BOM (platform) and Version Catalog
// It follows the approach of https://repo1.maven.org/maven2/io/micronaut/platform/micronaut-platform

plugins {
  id("java-platform")
  id("version-catalog")
  id("software.sava.build.feature.publish")
}

group = "software.sava"
version = providers.gradleProperty("version").getOrElse("")

// Plugins

// https://github.com/beryx/badass-jlink-plugin
val jlink = "3.2.0"

// https://plugins.gradle.org/plugin/com.google.protobuf
val googleProtobufPlugin = "0.9.6"

// Tests

// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-params
val junit = "6.0.2"

// Compile & Implementation

// https://github.com/sava-software/json-iterator
val savaJsonIterator = "21.0.11"

// https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk18on
// https://www.bouncycastle.org/download/bouncy-castle-java/#latest
val bouncyCastle = "1.81"

// https://central.sonatype.com/search?namespace=software.sava
val sava = "25.2.0"
val savaWeb2 = "25.1.0"
val savaPrograms = "25.0.0"
val savaAnchorPrograms = "25.1.4"

val savaIDLClients = "25.10.0"
val savaIDLClientsCore = savaIDLClients
val savaIDLClientsCCTP = savaIDLClients
val savaIDLClientsDrift = savaIDLClients
val savaIDLClientsJupiter = savaIDLClients
val savaIDLClientsKamino = savaIDLClients
val savaIDLClientsMarinade = savaIDLClients
val savaIDLClientsMetaplex = savaIDLClients
val savaIDLClientsMeteora = savaIDLClients
val savaIDLClientsOracles = savaIDLClients
val savaIDLClientsSPL = savaIDLClients

val glamIxProxy = "25.0.2"
val glamSDK = "25.4.0"

// https://central.sonatype.com/artifact/org.eclipse.jetty/jetty-server
val jetty = "12.1.5"
// https://mvnrepository.com/artifact/io.fusionauth/java-http
val fusionauthHttp = "1.4.0"

// https://central.sonatype.com/artifact/com.google.cloud/google-cloud-kms
val googleCloudKms = "2.83.0"
val savaRavina = "25.1.1"

// https://mvnrepository.com/artifact/io.grpc
val grpc = "1.76.0"
// https://github.com/grpc/grpc-java?tab=readme-ov-file#generated-code
// https://mvnrepository.com/artifact/com.google.protobuf/protoc
// https://mvnrepository.com/artifact/com.google.protobuf/protobuf-java
val googleProtobuf = "4.32.0"

// https://mvnrepository.com/artifact/org.slf4j/slf4j-jdk14
val slf4j = "2.0.17"

dependencies {
}

dependencies.constraints {
  // Tests
  api("org.junit.jupiter:junit-jupiter:${junit}")
  api("org.junit.jupiter:junit-jupiter-api:${junit}")
  api("org.junit.jupiter:junit-jupiter-params:${junit}")

  // Compile & Implementation
  api("software.sava:json-iterator:$savaJsonIterator")

  api("org.bouncycastle:bcprov-jdk18on:$bouncyCastle")

  api("software.sava:sava-core:$sava")
  api("software.sava:sava-rpc:$sava")

  api("software.sava:solana-web2:${savaWeb2}")

  api("software.sava:solana-programs:$savaPrograms")

  api("software.sava:anchor-programs:$savaAnchorPrograms")

  api("software.sava:idl-clients-core:${savaIDLClientsCore}")
  api("software.sava:idl-clients-cctp:$savaIDLClientsCCTP")
  api("software.sava:idl-clients-drift:$savaIDLClientsDrift")
  api("software.sava:idl-clients-jupiter:$savaIDLClientsJupiter")
  api("software.sava:idl-clients-kamino:$savaIDLClientsKamino")
  api("software.sava:idl-clients-marinade:${savaIDLClientsMarinade}")
  api("software.sava:idl-clients-metaplex:${savaIDLClientsMetaplex}")
  api("software.sava:idl-clients-meteora:${savaIDLClientsMeteora}")
  api("software.sava:idl-clients-oracles:$savaIDLClientsOracles")
  api("software.sava:idl-clients-spl:$savaIDLClientsSPL")

  api("software.sava:ravina-jetty:$savaRavina")
  api("software.sava:ravina-core:$savaRavina")
  api("software.sava:ravina-kms-core:$savaRavina")
  api("software.sava:ravina-kms-http:$savaRavina")
  api("software.sava:ravina-solana:$savaRavina")

  api("com.google.cloud:google-cloud-kms:$googleCloudKms")
  api("software.sava:ravina-kms-google:$savaRavina")

  api("systems.glam:ix-proxy:$glamIxProxy")
  api("systems.glam:sdk:${glamSDK}")

  // https://mvnrepository.com/artifact/org.eclipse.jetty/jetty-server
  api("org.eclipse.jetty:jetty-server:$jetty")
  // https://mvnrepository.com/artifact/org.eclipse.jetty.http2/jetty-http2-server
  api("org.eclipse.jetty.http2:jetty-http2-server:$jetty")
  // https://mvnrepository.com/artifact/org.eclipse.jetty/jetty-alpn-server
  api("org.eclipse.jetty:jetty-alpn-server:$jetty")
  api("org.eclipse.jetty:jetty-alpn-java-server:$jetty")
  // https://mvnrepository.com/artifact/org.eclipse.jetty/jetty-alpn-conscrypt-server
  api("org.eclipse.jetty:jetty-alpn-conscrypt-server:$jetty")
  // https://mvnrepository.com/artifact/org.eclipse.jetty.http3/jetty-http3-server
  api("org.eclipse.jetty.http3:jetty-http3-server:$jetty")
  // https://mvnrepository.com/artifact/org.eclipse.jetty.compression/jetty-compression-server
  api("org.eclipse.jetty.compression:jetty-compression-server:${jetty}")
  api("org.eclipse.jetty.compression:jetty-compression-gzip:${jetty}")
  api("org.eclipse.jetty.compression:jetty-compression-brotli:${jetty}")
  api("org.eclipse.jetty.compression:jetty-compression-zstandard:${jetty}")

  api("io.fusionauth:java-http:$fusionauthHttp")

  // https://mvnrepository.com/artifact/io.grpc/grpc-netty-shaded
  api("io.grpc:grpc-netty-shaded:$grpc")
  // https://mvnrepository.com/artifact/io.grpc/grpc-protobuf
  api("io.grpc:grpc-protobuf:$grpc")
  // https://mvnrepository.com/artifact/io.grpc/grpc-stub
  api("io.grpc:grpc-stub:$grpc")
  // https://mvnrepository.com/artifact/io.grpc/protoc-gen-grpc-java
  api("io.grpc:protoc-gen-grpc-java:$grpc")

  api("com.google.protobuf:protobuf-java:$googleProtobuf")

  api("org.slf4j:slf4j-jdk14:$slf4j")
}

catalog {
  // Library entries are derived from the BOM entries. The alias corresponds to the 'name' by default.
  // The cases where the alias should differ are defined below.
  configureExplicitAlias("bouncycastle", "org.bouncycastle", "bcprov-jdk18on")
  configureExplicitAlias("glam-ix-proxy", "systems.glam", "ix-proxy")
  configureExplicitAlias("glam-sdk", "systems.glam", "sdk")
  configureExplicitAlias("protoc-gen-grpc", "io.grpc", "protoc-gen-grpc-java")
  configurations.api.get().dependencyConstraints.forEach { constraint ->
    if (constraint.group == "software.sava" && !constraint.name.startsWith("sava")) {
      configureExplicitAlias("sava-${constraint.name}", constraint.group, constraint.name)
    }
  }

  versionCatalog {
    // Plugins
    plugin("jlink", "org.beryx.jlink").version(jlink)

    plugin("google-protobuf-plugin", "com.google.protobuf").version(googleProtobufPlugin)

    // Bundles
    bundle(
      "sava-solana-programs", listOf(
        "bouncycastle",
        "sava-json-iterator",
        "sava-core",
        "sava-rpc",
        "sava-solana-programs",
        "sava-idl-clients-core",
        "sava-idl-clients-drift",
        "sava-idl-clients-jupiter",
        "sava-idl-clients-kamino",
        "sava-idl-clients-marinade",
        "sava-idl-clients-metaplex",
        "sava-idl-clients-meteora",
        "sava-idl-clients-oracles"
      )
    )

    bundle("glam", listOf("glam-ix-proxy", "glam-sdk"))

    bundle(
      "jetty", listOf(
        "jetty-server",
        "jetty-http2-server",
        "jetty-alpn-server",
        "jetty-alpn-java-server",
        "jetty-alpn-conscrypt-server",
        "jetty-http3-server",
        "jetty-compression-server",
        "jetty-compression-gzip"
      )
    )

    bundle(
      "grpc-protobuf", listOf(
        "grpc-netty-shaded",
        "grpc-protobuf",
        "grpc-stub",
        "protobuf-java",
        "protoc-gen-grpc"
      )
    )

    // Versions
    version("grpc", grpc)
    version("protoc", googleProtobuf)
  }
}
