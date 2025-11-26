package com.jaf.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoverageRuntimeTest {

    @BeforeEach
    void resetCoverage() {
        CoverageRuntime.reset();
    }

    @Test
    void incrementingEdgeProducesNonZeroEntry() {
        CoverageRuntime.startTracing();
        CoverageRuntime.enterEdge(42);
        CoverageRuntime.enterEdge(7);
        byte[] trace = CoverageRuntime.stopTracing();

        CoverageMaps.mergeIntoGlobal(trace);

        assertNotEquals(0, CoverageRuntime.nonZeroCount());
    }

    @Test
    void resetClearsCoverageMap() {
        CoverageRuntime.startTracing();
        CoverageRuntime.enterEdge(10);
        byte[] trace = CoverageRuntime.stopTracing();
        CoverageMaps.mergeIntoGlobal(trace);
        assertNotEquals(0, CoverageRuntime.nonZeroCount());

        CoverageRuntime.reset();

        assertEquals(0, CoverageRuntime.nonZeroCount());
    }

    @Test
    void snapshotReturnsCopy() {
        CoverageRuntime.startTracing();
        CoverageRuntime.enterEdge(5);
        byte[] trace = CoverageRuntime.stopTracing();

        CoverageMaps.mergeIntoGlobal(trace);

        byte[] snapshot = CoverageRuntime.snapshot();
        snapshot[0] = (byte) 0x7F;

        byte[] secondSnapshot = CoverageRuntime.snapshot();
        assertNotEquals(0x7F, secondSnapshot[0] & 0xFF);
    }

    @Test
    void hasNewCoverageDetectsPreviouslyUnseenEdge() {
        CoverageRuntime.startTracing();
        CoverageRuntime.enterEdge(15);
        byte[] trace = CoverageRuntime.stopTracing();

        assertTrue(CoverageMaps.hasNewCoverage(trace));
        CoverageMaps.mergeIntoGlobal(trace);
        assertFalse(CoverageMaps.hasNewCoverage(trace));
    }
}
