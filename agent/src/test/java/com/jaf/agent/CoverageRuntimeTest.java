package com.jaf.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoverageRuntimeTest {

    @BeforeEach
    void resetCoverage() {
        CoverageRuntime.reset();
    }

    @Test
    void incrementingEdgeProducesNonZeroEntry() {
        CoverageRuntime.enterEdge(42);
        CoverageRuntime.enterEdge(7);

        assertNotEquals(0, CoverageRuntime.nonZeroCount());
    }

    @Test
    void resetClearsCoverageMap() {
        CoverageRuntime.enterEdge(10);
        assertNotEquals(0, CoverageRuntime.nonZeroCount());

        CoverageRuntime.reset();

        assertEquals(0, CoverageRuntime.nonZeroCount());
    }

    @Test
    void snapshotReturnsCopy() {
        CoverageRuntime.enterEdge(5);
        byte[] snapshot = CoverageRuntime.snapshot();
        snapshot[0] = (byte) 0x7F;

        byte[] secondSnapshot = CoverageRuntime.snapshot();
        assertNotEquals(0x7F, secondSnapshot[0] & 0xFF);
    }
}
