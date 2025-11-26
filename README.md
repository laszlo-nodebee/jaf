# Java API Fuzzer (JAF)

- Coverage-guided, grammar-aware fuzzer inspired by NAUTILUS (paper: https://www.ndss-symposium.org/ndss-paper/nautilus-fishing-for-deep-bugs-with-grammars/). Generates JSON inputs from a configurable grammar, collects edge coverage via a Java agent, and drives an HTTP SUT.
- Modules: `protocol` (gRPC coverage schema), `agent` (javaagent that instruments the JVM and streams coverage over a Unix domain socket), `fuzzer` (Nautilus-style queue, grammar, and HTTP executor), `demo` (Spring Boot target for local runs).
- Requirements: JDK 17+

Note that this project is a work in progress and not yet suitable for production use.

## Build
- `./gradlew build` (generates protobuf code, builds agent/fuzzer/demo jars, runs tests).

## Quick start (demo target)
- Build everything: `./gradlew build`
- Start instrumented demo API: `java -javaagent:agent/build/libs/jaf-agent-0.1.0.jar -jar demo/build/libs/demo-0.1.0.jar`
- In another shell, run fuzzer for 2 minutes: `./gradlew :fuzzer:run --args='--duration=120'`
- Fuzzer CLI flags: `--duration=<seconds>` (default 30; `<=0` runs ~1h), `--socket=<path>` (default `/tmp/jaf-coverage.sock`), `--sut=<http-url>` (default `http://127.0.0.1:8080/api/system/id`).

## How it works
- Agent (`agent/`) installs ASM transformers to track HTTP requests based on their`X-Fuzzing-Request-Id` header, log dangerous sinks, and count edges; publishes coverage via gRPC on `/tmp/jaf-coverage.sock`.
- Fuzzer (`fuzzer/`) uses a Nautilus-inspired generator/mutator over a JSON object grammar (`JafFuzzer#buildDefaultGrammar`), executes inputs against the target over HTTP, and keeps inputs that reveal new edges.
- Protocol (`protocol/`) defines the `CoverageService` used by both sides; Gradle’s protobuf plugin generates stubs.
- Demo (`demo/`) is a small Spring Boot service to fuzz locally; replace its URL with your own SUT via `--sut`.

## Testing
- `./gradlew test` runs unit tests for fuzzer and agent components.
