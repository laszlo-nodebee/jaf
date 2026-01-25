package com.jaf.fuzzer.nautilus.exec;

import com.jaf.fuzzer.coverage.CoverageBitmap;
import java.util.Objects;

/**
 * Outcome of executing the target with a fuzz input. Coverage is expressed as an AFL-style
 * bitmap to enable "new transition" checks within the Nautilus queue.
 */
public final class ExecutionResult {
    public final boolean crashed;
    public final CoverageBitmap edges;
    public final byte[] stderr;

    public ExecutionResult(boolean crashed, CoverageBitmap edges, byte[] stderr) {
        this.crashed = crashed;
        this.edges = Objects.requireNonNull(edges, "edges");
        this.stderr = Objects.requireNonNull(stderr, "stderr");
    }
}
