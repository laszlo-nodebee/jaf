import org.gradle.api.file.DuplicatesStrategy

dependencies {
    implementation("org.ow2.asm:asm:9.9")
    implementation(project(":protocol"))
    implementation("io.grpc:grpc-netty-shaded:1.76.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.0")
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
