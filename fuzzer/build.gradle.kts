plugins {
    application
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
