plugins {
  id("version-catalog")
  id("maven-publish")
}

// Plugins

// https://github.com/beryx/badass-jlink-plugin
val jlink = "3.1.1"

// https://plugins.gradle.org/plugin/com.google.protobuf
val googleProtobufPlugin = "0.9.5"

// Tests

// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
// https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
val junit = "5.13.1"

// Compile & Implementation

// Deprecated
// https://github.com/comodal/json-iterator/tags
val jsonIterator = "2.13.2"

// https://github.com/sava-software/json-iterator
val savaJsonIterator = "2.14.0"

// https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk18on
// https://www.bouncycastle.org/download/bouncy-castle-java/#latest
val bouncyCastle = "1.80"

val sava = "1.19.0"
val savaWeb2 = "1.12.0"
val savaPrograms = "1.20.0"
val savaSrcGen = "1.10.0"
val savaAnchorPrograms = "1.17.7"

val glamIxProxy = "0.3.2"

// https://mvnrepository.com/artifact/org.eclipse.jetty/jetty-server
val jetty = "12.0.22"
// https://mvnrepository.com/artifact/com.google.cloud/google-cloud-kms
val googleCloudKms = "2.67.0"
val savaRavina = "0.23.2"

// Deprecated
val savaKms = "0.3.1"

// https://mvnrepository.com/artifact/io.grpc
val grpc = "1.73.0"
// https://github.com/grpc/grpc-java?tab=readme-ov-file#generated-code
// https://mvnrepository.com/artifact/com.google.protobuf/protoc
val protoc = "3.25.5"
// https://mvnrepository.com/artifact/org.apache.tomcat/tomcat-annotations-api
val apacheTomcat = "6.0.53"

catalog {
  versionCatalog {
    // Plugins

    plugin("jlink", "org.beryx.jlink").version(jlink)

    plugin("google-protobuf-plugin", "com.google.protobuf").version(googleProtobufPlugin)

    // Tests

    library("junit-jupiter", "org.junit.jupiter:junit-jupiter:$junit")
    library("junit-jupiter-api", "org.junit.jupiter:junit-jupiter-api:$junit")

    // Compile & Implementation

    // Deprecated
    library("json-iterator", "systems.comodal:json-iterator:$jsonIterator")
    // Replaces above.
    library("sava-json-iterator", "software.sava:json-iterator:$savaJsonIterator")

    library("bouncycastle", "org.bouncycastle:bcprov-jdk18on:$bouncyCastle")

    library("sava-core", "software.sava:sava-core:$sava")
    library("sava-rpc", "software.sava:sava-rpc:$sava")

    library("sava-solana-programs", "software.sava:solana-programs:$savaPrograms")

    library("sava-anchor-src-gen", "software.sava:anchor-src-gen:$savaSrcGen")
    library("sava-anchor-programs", "software.sava:anchor-programs:$savaAnchorPrograms")

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

    library("sava-solana-web2", "software.sava:solana-web2:$savaWeb2")

    library("sava-ravina-jetty", "software.sava:ravina-jetty:$savaRavina")
    library("sava-ravina-core", "software.sava:ravina-core:$savaRavina")
    library("sava-ravina-kms-core", "software.sava:ravina-kms-core:$savaRavina")
    library("sava-ravina-kms-http", "software.sava:ravina-kms-http:$savaRavina")
    library("sava-ravina-solana", "software.sava:ravina-solana:$savaRavina")

    library("google-cloud-kms", "com.google.cloud:google-cloud-kms:$googleCloudKms")
    library("sava-ravina-kms-google", "software.sava:ravina-kms-google:$savaRavina")

    // Deprecated
    library("sava-kms-core", "software.sava:kms_core:$savaKms")
    library("sava-http-kms", "software.sava:http_kms:$savaKms")
    library("sava-google-kms", "software.sava:google_kms:$savaKms")

    library("glam-ix-proxy", "systems.glam:ix-proxy:$glamIxProxy")

    // https://mvnrepository.com/artifact/org.eclipse.jetty/jetty-server
    library("jetty-server", "org.eclipse.jetty:jetty-server:$jetty")
    // https://mvnrepository.com/artifact/org.eclipse.jetty.http2/jetty-http2-server
    library("jetty-http2-server", "org.eclipse.jetty.http2:jetty-http2-server:$jetty")
    // https://mvnrepository.com/artifact/org.eclipse.jetty/jetty-alpn-server
    library("jetty-alpn-server", "org.eclipse.jetty:jetty-alpn-server:$jetty")
    library("jetty-alpn-java-server", "org.eclipse.jetty:jetty-alpn-java-server:$jetty")
    // https://mvnrepository.com/artifact/org.eclipse.jetty/jetty-alpn-conscrypt-server
    library("jetty-alpn-conscrypt-server", "org.eclipse.jetty:jetty-alpn-conscrypt-server:$jetty")
    // https://mvnrepository.com/artifact/org.eclipse.jetty.http3/jetty-http3-server
    library("jetty-http3-server", "org.eclipse.jetty.http3:jetty-http3-server:$jetty")

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

    version("grpc", grpc)
    version("protoc", protoc)

    // https://mvnrepository.com/artifact/io.grpc/grpc-netty-shaded
    library("grpc-netty-shaded", "io.grpc:grpc-netty-shaded:$grpc")
    // https://mvnrepository.com/artifact/io.grpc/grpc-protobuf
    library("grpc-protobuf", "io.grpc:grpc-protobuf:$grpc")
    // https://mvnrepository.com/artifact/io.grpc/grpc-stub
    library("grpc-stub", "io.grpc:grpc-stub:$grpc")
    // https://mvnrepository.com/artifact/io.grpc/protoc-gen-grpc-java
    library("protoc-gen-grpc", "io.grpc:protoc-gen-grpc-java:$grpc")
    library("apache-tomcat-annotations-api", "org.apache.tomcat:annotations-api:$apacheTomcat")

    bundle(
      "grpc-protobuf", listOf(
        "grpc-netty-shaded",
        "grpc-protobuf",
        "grpc-stub",
        "protoc-gen-grpc",
        "apache-tomcat-annotations-api"
      )
    )
  }
}

group = "software.sava"
version = project.findProperty("version") as String

val gprUser =
  providers.gradleProperty("gpr.user.write").orElse(providers.environmentVariable("GITHUB_ACTOR")).orElse("")
val gprToken =
  providers.gradleProperty("gpr.token.write").orElse(providers.environmentVariable("GITHUB_TOKEN")).orElse("")

publishing {
  publications.register<MavenPublication>("maven") {
    from(components["versionCatalog"])
  }
  repositories {
    maven {
      name = "GithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/solana-version-catalog")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
  }
}
