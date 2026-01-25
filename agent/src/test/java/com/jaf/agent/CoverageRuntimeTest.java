package com.jaf.agent;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertNotEquals(0, countNonZero(trace));
    }

    @Test
    void resetClearsCoverageMap() {
        CoverageRuntime.startTracing();
        CoverageRuntime.enterEdge(10);
        assertNotEquals(0, countNonZero(CoverageRuntime.currentTraceBitmap()));

        CoverageRuntime.reset();

        assertNull(CoverageRuntime.currentTraceBitmap());
    }

    @Test
    void snapshotReturnsCopy() {
        CoverageRuntime.startTracing();
        CoverageRuntime.enterEdge(5);
        CoverageRuntime.stopTracing();

        byte[] snapshot = CoverageRuntime.snapshot();
        snapshot[0] = (byte) 0x7F;

        byte[] secondSnapshot = CoverageRuntime.snapshot();
        assertNotEquals(0x7F, secondSnapshot[0] & 0xFF);
    }

    private static int countNonZero(byte[] bitmap) {
        if (bitmap == null) {
            return 0;
        }
        int count = 0;
        for (byte value : bitmap) {
            if (value != 0) {
                count++;
            }
        }
        return count;
    }
}
