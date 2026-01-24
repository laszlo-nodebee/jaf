package com.jaf.fuzzer.nautilus.core;

import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DeterminismChecker {
    public interface Runner {
        ExecutionResult run(byte[] input);
    }

    private static volatile boolean debugEnabled = false;

    private final Runner runner;
    private final int runs;
    private final Map<Integer, Integer> flakyEdges = new HashMap<>();

    public static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    public DeterminismChecker(Runner runner, int runs) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.runs = Math.max(1, runs);
    }

    public Set<Integer> filterKnownFlakyEdges(Set<Integer> edges) {
        if (edges.isEmpty() || flakyEdges.isEmpty()) {
            return new HashSet<>(edges);
        }
        Set<Integer> filtered = new HashSet<>(edges);
        filtered.removeAll(flakyEdges.keySet());
        return filtered;
    }

    public void recordFlakyEdges(byte[] input, Set<Integer> firstEdges) {
        if (runs <= 1) {
            debug("Skipping determinism check (runs=" + runs + ")");
            return;
        }
        Set<Integer> initial = filterKnownFlakyEdges(firstEdges);
        if (initial.isEmpty()) {
            debug("Skipping determinism check (no edges after filtering)");
            return;
        }
        debug("Determinism check start runs=" + runs + " initial=" + initial.size());
        Set<Integer> intersection = new HashSet<>(initial);
        Set<Integer> union = new HashSet<>(initial);
        for (int i = 1; i < runs; i++) {
            ExecutionResult result = runner.run(input);
            Set<Integer> edges = filterKnownFlakyEdges(result.edges);
            union.addAll(edges);
            intersection.retainAll(edges);
        }
        Set<Integer> newlyFlaky = new HashSet<>(union);
        newlyFlaky.removeAll(intersection);
        if (!newlyFlaky.isEmpty()) {
            debug("Determinism check found flaky=" + newlyFlaky.size());
            recordFlakyEdges(newlyFlaky);
        } else {
            debug("Determinism check found no flaky edges");
        }
    }

    private void recordFlakyEdges(Set<Integer> edges) {
        for (Integer edge : edges) {
            flakyEdges.merge(edge, 1, Integer::sum);
        }
    }

    private void debug(String message) {
        if (debugEnabled) {
            System.out.println("[DeterminismChecker] " + message);
        }
    }
}
