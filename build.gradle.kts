import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins {
    base
    id("com.google.protobuf") version "0.9.4" apply false
}

group = "com.jaf"
version = "0.1.0"

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
