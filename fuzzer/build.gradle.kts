plugins {
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation("io.grpc:grpc-netty-shaded:1.76.0")
}

application {
    mainClass.set("com.jaf.fuzzer.JafFuzzer")
}

tasks.jar {
    archiveBaseName.set("jaf-fuzzer")
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}
