package com.jaf.fuzzer.nautilus.exec;

import java.util.Objects;
import java.util.Set;

/**
 * Outcome of executing the target with a fuzz input. Coverage is expressed as a set of edge
 * identifiers to enable "new transition" checks within the Nautilus queue.
 */
public final class ExecutionResult {
    public final boolean crashed;
    public final Set<Integer> edges;
    public final byte[] stderr;

    public ExecutionResult(boolean crashed, Set<Integer> edges, byte[] stderr) {
        this.crashed = crashed;
        this.edges = Objects.requireNonNull(edges, "edges");
        this.stderr = Objects.requireNonNull(stderr, "stderr");
    }
}
