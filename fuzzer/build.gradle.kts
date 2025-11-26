plugins {
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation("io.grpc:grpc-netty-shaded:1.76.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("io.grpc:grpc-testing:1.76.0")
}

application {
    mainClass.set("com.jaf.fuzzer.JafFuzzer")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("jaf-fuzzer")
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}
