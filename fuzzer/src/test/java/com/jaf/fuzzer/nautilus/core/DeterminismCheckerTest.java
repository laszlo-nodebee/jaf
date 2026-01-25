package com.jaf.fuzzer.nautilus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaf.fuzzer.coverage.CoverageBitmap;
import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import java.util.ArrayDeque;
import java.util.Deque;
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
                            return new ExecutionResult(false, CoverageBitmap.empty(), new byte[0]);
                        },
                        1);

        checker.recordFlakyEdges(new byte[0], CoverageBitmap.fromIndices(1, 2));

        assertEquals(0, calls.get(), "runner should not be invoked when runs is 1");
        CoverageBitmap untouched =
                checker.filterKnownFlakyEdges(CoverageBitmap.fromIndices(1, 2));
        assertEquals(2, untouched.countNonZero());
        assertTrue(untouched.covers(CoverageBitmap.fromIndices(1, 2)));
    }

    @Test
    void recordFlakyEdgesMarksMissingEdgesFlaky() {
        DeterminismChecker checker =
                new DeterminismChecker(
                        new QueueRunner(
                                CoverageBitmap.fromIndices(1, 2), CoverageBitmap.fromIndices(1)),
                        3);

        checker.recordFlakyEdges(new byte[0], CoverageBitmap.fromIndices(1, 2));

        CoverageBitmap filtered =
                checker.filterKnownFlakyEdges(CoverageBitmap.fromIndices(1, 2));
        assertEquals(1, filtered.countNonZero());
        assertTrue(filtered.covers(CoverageBitmap.fromIndices(1)));
        assertTrue(checker.filterKnownFlakyEdges(CoverageBitmap.fromIndices(2)).isEmpty());
    }

    @Test
    void recordFlakyEdgesMarksEdgesThatAppearLater() {
        DeterminismChecker checker =
                new DeterminismChecker(
                        new QueueRunner(
                                CoverageBitmap.fromIndices(1, 3),
                                CoverageBitmap.fromIndices(1, 3)),
                        3);

        checker.recordFlakyEdges(new byte[0], CoverageBitmap.fromIndices(1));

        CoverageBitmap filtered =
                checker.filterKnownFlakyEdges(CoverageBitmap.fromIndices(1, 3));
        assertEquals(1, filtered.countNonZero());
        assertTrue(filtered.covers(CoverageBitmap.fromIndices(1)));
    }

    private static final class QueueRunner implements DeterminismChecker.Runner {
        private final Deque<CoverageBitmap> results = new ArrayDeque<>();

        private QueueRunner(CoverageBitmap... edges) {
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
