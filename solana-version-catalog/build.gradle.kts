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
val jlink = "3.1.1"

// https://plugins.gradle.org/plugin/com.google.protobuf
val googleProtobufPlugin = "0.9.5"

// Tests

// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-params
val junit = "5.13.2"

// Compile & Implementation

// Deprecated
// https://github.com/comodal/json-iterator/tags
val jsonIterator = "2.13.2"
// https://github.com/sava-software/json-iterator
val savaJsonIterator = "21.0.8"

// https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk18on
// https://www.bouncycastle.org/download/bouncy-castle-java/#latest
val bouncyCastle = "1.81"

// https://central.sonatype.com/search?namespace=software.sava
val sava = "24.19.6"
val savaWeb2 = "1.12.0"
val savaPrograms = "24.20.3"
val savaSrcGen = "1.10.0"
val savaAnchorPrograms = "1.17.13"

val glamIxProxy = "0.3.2"

// https://mvnrepository.com/artifact/org.eclipse.jetty/jetty-server
val jetty = "12.0.22"
// https://mvnrepository.com/artifact/com.google.cloud/google-cloud-kms
val googleCloudKms = "2.67.0"
val savaRavina = "0.23.3"

// Deprecated
val savaKms = "0.3.1"

// https://mvnrepository.com/artifact/io.grpc
val grpc = "1.73.0"
// https://github.com/grpc/grpc-java?tab=readme-ov-file#generated-code
// https://mvnrepository.com/artifact/com.google.protobuf/protoc
val protoc = "3.25.5"
// https://mvnrepository.com/artifact/org.apache.tomcat/tomcat-annotations-api
val apacheTomcat = "6.0.53"

dependencies.constraints {
  // Tests

  api("org.junit.jupiter:junit-jupiter:$junit")
  api("org.junit.jupiter:junit-jupiter-api:$junit")
  api("org.junit.jupiter:junit-jupiter-params:${junit}")

  // Compile & Implementation

  // Deprecated
  api("systems.comodal:json-iterator:$jsonIterator")
  // Replaces above.
  api("software.sava:json-iterator:$savaJsonIterator")

  api("org.bouncycastle:bcprov-jdk18on:$bouncyCastle")

  api("software.sava:sava-core:$sava")
  api("software.sava:sava-rpc:$sava")

  api("software.sava:solana-programs:$savaPrograms")

  api("software.sava:anchor-src-gen:$savaSrcGen")
  api("software.sava:anchor-programs:$savaAnchorPrograms")

  api("software.sava:solana-web2:$savaWeb2")

  api("software.sava:ravina-jetty:$savaRavina")
  api("software.sava:ravina-core:$savaRavina")
  api("software.sava:ravina-kms-core:$savaRavina")
  api("software.sava:ravina-kms-http:$savaRavina")
  api("software.sava:ravina-solana:$savaRavina")

  api("com.google.cloud:google-cloud-kms:$googleCloudKms")
  api("software.sava:ravina-kms-google:$savaRavina")

  // Deprecated
  api("software.sava:kms_core:$savaKms")
  api("software.sava:http_kms:$savaKms")
  api("software.sava:google_kms:$savaKms")

  api("systems.glam:ix-proxy:$glamIxProxy")

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

  // https://mvnrepository.com/artifact/io.grpc/grpc-netty-shaded
  api("io.grpc:grpc-netty-shaded:$grpc")
  // https://mvnrepository.com/artifact/io.grpc/grpc-protobuf
  api("io.grpc:grpc-protobuf:$grpc")
  // https://mvnrepository.com/artifact/io.grpc/grpc-stub
  api("io.grpc:grpc-stub:$grpc")
  // https://mvnrepository.com/artifact/io.grpc/protoc-gen-grpc-java
  api("io.grpc:protoc-gen-grpc-java:$grpc")
  api("org.apache.tomcat:annotations-api:$apacheTomcat")
}

catalog {
  // Library entries are derived from the BOM entries. The alias corresponds to the 'name' by default.
  // The cases where the alias should differ are defined below.
  configureExplicitAlias("apache-tomcat-annotations-api", "org.apache.tomcat", "annotations-api")
  configureExplicitAlias("bouncycastle", "org.bouncycastle", "bcprov-jdk18on")
  configureExplicitAlias("glam-ix-proxy", "systems.glam", "ix-proxy")
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
        "sava-anchor-src-gen",
        "sava-anchor-programs"
      )
    )

    bundle(
      "jetty", listOf(
        "jetty-server",
        "jetty-http2-server",
        "jetty-alpn-server",
        "jetty-alpn-java-server",
        "jetty-alpn-conscrypt-server",
        "jetty-http3-server"
      )
    )

    bundle(
      "grpc-protobuf", listOf(
        "grpc-netty-shaded",
        "grpc-protobuf",
        "grpc-stub",
        "protoc-gen-grpc",
        "apache-tomcat-annotations-api"
      )
    )

    // Versions
    version("grpc", grpc)
    version("protoc", protoc)
  }
}
