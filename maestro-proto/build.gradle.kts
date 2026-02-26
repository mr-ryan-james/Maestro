
plugins {
    id("maven-publish")
    java
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    publishToMavenCentral(true)
    signAllPublications()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.named<Jar>("jar") {
    from("src/main/proto/maestro_android.proto")
}
