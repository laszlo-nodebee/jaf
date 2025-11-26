plugins {
    id("java-library")
    id("com.google.protobuf")
}

repositories {
    mavenCentral()
}

dependencies {
    api("io.grpc:grpc-stub:1.76.0")
    api("io.grpc:grpc-protobuf:1.76.0")
    api("com.google.protobuf:protobuf-java:4.33.0")
}

sourceSets {
    main {
        java {
            srcDir("build/generated/source/proto/main/java")
            srcDir("build/generated/source/proto/main/grpc")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.33.0"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.76.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
