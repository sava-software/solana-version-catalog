plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  nmcpAggregation(project(":solana-version-catalog"))
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(":solana-version-catalog:publishMavenJavaPublicationToSavaGithubPackagesRepository")
}
