package com.jaf.fuzzer.nautilus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DeterminismCheckerTest {

    @Test
    void recordFlakyEdgesNoopsWhenRunsIsOne() {
        AtomicInteger calls = new AtomicInteger();
        DeterminismChecker checker =
                new DeterminismChecker(
                        input -> {
                            calls.incrementAndGet();
                            return new ExecutionResult(false, Set.of(), new byte[0]);
                        },
                        1);

        checker.recordFlakyEdges(new byte[0], Set.of(1, 2));

        assertEquals(0, calls.get(), "runner should not be invoked when runs is 1");
        assertEquals(Set.of(1, 2), checker.filterKnownFlakyEdges(Set.of(1, 2)));
    }

    @Test
    void recordFlakyEdgesMarksMissingEdgesFlaky() {
        DeterminismChecker checker =
                new DeterminismChecker(
                        new QueueRunner(Set.of(1, 2), Set.of(1)),
                        3);

        checker.recordFlakyEdges(new byte[0], Set.of(1, 2));

        assertEquals(Set.of(1), checker.filterKnownFlakyEdges(Set.of(1, 2)));
        assertTrue(checker.filterKnownFlakyEdges(Set.of(2)).isEmpty());
    }

    @Test
    void recordFlakyEdgesMarksEdgesThatAppearLater() {
        DeterminismChecker checker =
                new DeterminismChecker(
                        new QueueRunner(Set.of(1, 3), Set.of(1, 3)),
                        3);

        checker.recordFlakyEdges(new byte[0], Set.of(1));

        assertEquals(Set.of(1), checker.filterKnownFlakyEdges(Set.of(1, 3)));
    }

    private static final class QueueRunner implements DeterminismChecker.Runner {
        private final Deque<Set<Integer>> results = new ArrayDeque<>();

        private QueueRunner(Set<Integer>... edges) {
            results.addAll(java.util.List.of(edges));
        }

        @Override
        public ExecutionResult run(byte[] input) {
            if (results.isEmpty()) {
                throw new AssertionError("Unexpected runner invocation");
            }
            return new ExecutionResult(false, results.removeFirst(), new byte[0]);
        }
    }
}
