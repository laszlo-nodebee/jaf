import org.gradle.api.file.DuplicatesStrategy

plugins {
    `java`
}

group = "com.example"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.9")
}

tasks.jar {
    archiveBaseName.set("hello-agent")
    manifest {
        from(file("agent-manifest.mf"))
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    )
}
