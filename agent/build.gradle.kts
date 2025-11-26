import org.gradle.api.file.DuplicatesStrategy

dependencies {
    implementation("org.ow2.asm:asm:9.9")
}

tasks.jar {
    archiveBaseName.set("jaf-agent")
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Premain-Class" to "com.jaf.agent.JafAgent",
            "Can-Retransform-Classes" to "true"
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    )
}
