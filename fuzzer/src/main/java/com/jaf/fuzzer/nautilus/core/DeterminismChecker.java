package com.jaf.fuzzer.nautilus.core;

import com.jaf.fuzzer.coverage.CoverageBitmap;
import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import java.util.BitSet;
import java.util.Objects;

public final class DeterminismChecker {
    public interface Runner {
        ExecutionResult run(byte[] input);
    }

    private static volatile boolean debugEnabled = false;

    private final Runner runner;
    private final int runs;
    private final BitSet flakyEdges = new BitSet();

    public static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    public DeterminismChecker(Runner runner, int runs) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.runs = Math.max(1, runs);
    }

    public CoverageBitmap filterKnownFlakyEdges(CoverageBitmap edges) {
        if (edges.isEmpty() || flakyEdges.isEmpty()) {
            return edges;
        }
        return edges.without(flakyEdges);
    }

    public void recordFlakyEdges(byte[] input, CoverageBitmap firstEdges) {
        if (runs <= 1) {
            debug("Skipping determinism check (runs=" + runs + ")");
            return;
        }
        CoverageBitmap initial = filterKnownFlakyEdges(firstEdges);
        if (initial.isEmpty()) {
            debug("Skipping determinism check (no edges after filtering)");
            return;
        }
        debug(
                "Determinism check start runs=" + runs + " initial=" + initial.countNonZero());
        CoverageBitmap intersection = initial;
        CoverageBitmap union = initial;
        for (int i = 1; i < runs; i++) {
            ExecutionResult result = runner.run(input);
            CoverageBitmap edges = filterKnownFlakyEdges(result.edges);
            union = union.union(edges);
            intersection = intersection.intersect(edges);
        }
        CoverageBitmap newlyFlaky = union.minus(intersection);
        if (!newlyFlaky.isEmpty()) {
            debug("Determinism check found flaky=" + newlyFlaky.countNonZero());
            recordFlakyEdges(newlyFlaky);
        } else {
            debug("Determinism check found no flaky edges");
        }
    }

    private void recordFlakyEdges(CoverageBitmap edges) {
        edges.forEachSetIndex(flakyEdges::set);
    }

    private void debug(String message) {
        if (debugEnabled) {
            System.out.println("[DeterminismChecker] " + message);
        }
    }
}
