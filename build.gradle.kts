import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins {
    base
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

    dependencies {
        add("testImplementation", "org.junit.jupiter:junit-jupiter-api:5.10.2")
        add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine:5.10.2")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.10.2")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
