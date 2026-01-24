# Repository Guidelines

## Project Structure & Module Organization
This is a multi-module Gradle project. Core modules live at the repo root:
- `agent/` Java agent that instruments the JVM and streams coverage over gRPC.
- `fuzzer/` Nautilus-style grammar fuzzer and HTTP executor.
- `protocol/` protobuf definitions and generated gRPC stubs.
- `demo/` Spring Boot service used as a local fuzzing target.
Supporting folders include `docs/` and `examples/`. Build outputs land in `*/build/`.

## Build, Test, and Development Commands
- `./gradlew build` generates protobuf code, builds all jars, and runs tests.
- `./gradlew test` runs unit tests across modules.
- `./gradlew :demo:bootRun` starts the Spring Boot demo service.
- `java -javaagent:agent/build/libs/jaf-agent-0.1.0.jar -jar demo/build/libs/demo-0.1.0.jar` runs the demo with instrumentation.
- `./gradlew :fuzzer:run --args='--duration=120'` runs the fuzzer; use `--socket=/tmp/jaf-coverage.sock` and `--sut=http://127.0.0.1:8080/api/system/id` to point at a SUT.

## Coding Style & Naming Conventions
- Java 17+, 4-space indentation, and standard Java formatting conventions.
- Packages follow `com.jaf.<module>`; classes use `PascalCase`, methods/fields `camelCase`.
- Tests live in `src/test/java` and mirror production package names.
- No formatter or linter is configured; keep diffs clean and consistent.

## Testing Guidelines
- JUnit 5 is used across modules (JUnit Platform enabled in Gradle).
- Name test classes `*Test` (for example, `CoverageRuntimeTest`).
- Run focused tests with `./gradlew :agent:test` or `./gradlew :fuzzer:test`.
- After any code changes, run unit tests (at least the affected module).

## Commit & Pull Request Guidelines
- Recent commits use short, imperative summaries (for example, “add …”, “Fix …”).
- Keep the subject line concise; add details in the body when behavior changes.
- PRs should include a brief description, testing performed, and any demo logs or command output relevant to fuzzing runs.

## Security & Configuration Tips
- The coverage socket defaults to `/tmp/jaf-coverage.sock`; ensure the path is writable and not shared across concurrent runs.
- When fuzzing non-demo targets, pass the SUT URL via `--sut` and keep credentials out of repo files.
