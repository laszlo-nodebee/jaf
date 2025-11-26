#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

./gradlew :agent:jar
./gradlew :demo:bootJar

java -javaagent:agent/build/libs/jaf-agent-0.1.0.jar -jar demo/build/libs/demo-0.1.0.jar &
pid_demo=$!

cleanup() {
    trap - EXIT
    if [[ -n "${pid_fuzzer:-}" ]] && kill -0 "$pid_fuzzer" 2>/dev/null; then
        kill "$pid_fuzzer" 2>/dev/null || true
        wait "$pid_fuzzer" 2>/dev/null || true
    fi
    if [[ -n "${pid_demo:-}" ]] && kill -0 "$pid_demo" 2>/dev/null; then
        kill "$pid_demo" 2>/dev/null || true
        wait "$pid_demo" 2>/dev/null || true
    fi
}
trap cleanup EXIT

sleep 1
./gradlew :fuzzer:run --args='--duration=20' &
pid_fuzzer=$!

wait_for_port() {
    local host=$1
    local port=$2
    local attempts=${3:-30}
    local i
    for ((i = 0; i < attempts; i++)); do
        if (echo >"/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for ${host}:${port}" >&2
    return 1
}

wait_for_port "127.0.0.1" 8080 60
curl --fail --silent --show-error \
    -H"X-Fuzzing-Request-Id: asdf1234" \
    -H"Content-Type: application/json" \
    --request POST \
    --data '{"command":"id"}' \
    http://127.0.0.1:8080/api/system/id

sleep 2

cleanup
