import org.gradle.api.tasks.compile.JavaCompile

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
}
