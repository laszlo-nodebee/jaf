package com.jaf.agent;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Coverage runtime modeled after AFL's edge coverage scheme.
 *
 * <p>Instrumented code calls {@link #enterEdge(int)} at the beginning of each basic block. The
 * runtime tracks a rolling previous-location value per thread and bumps a byte-sized counter in the
 * global coverage map using {@code prev ^ cur} as the index.</p>
 */
public final class CoverageRuntime {
    static final int MAP_SIZE = 1 << 16; // 64K entries, must stay a power of two.

    private static final byte[] COVERAGE_MAP = new byte[MAP_SIZE];
    private static final ThreadLocal<Integer> PREVIOUS_LOCATION =
            ThreadLocal.withInitial(() -> 0);
    private static final List<CoverageEventListener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private CoverageRuntime() {}

    /**
     * Records the execution of an edge identified by the provided block identifier.
     *
     * @param currentLocation pre-hashed block identifier (must already fit into {@link #MAP_SIZE})
     */
    public static void enterEdge(int currentLocation) {
        int prev = PREVIOUS_LOCATION.get();
        int index = (prev ^ currentLocation) & (MAP_SIZE - 1);

        byte value = COVERAGE_MAP[index];
        if (value != (byte) 0xFF) {
            byte updated = (byte) (value + 1);
            COVERAGE_MAP[index] = updated;
            if (value == 0) {
                notifyNewEdge(index);
            }
        }

        PREVIOUS_LOCATION.set((currentLocation >>> 1) & (MAP_SIZE - 1));
    }

    public static void registerListener(CoverageEventListener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void unregisterListener(CoverageEventListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    /** Resets the global coverage map and the current thread's previous-location pointer. */
    public static void reset() {
        Arrays.fill(COVERAGE_MAP, (byte) 0);
        PREVIOUS_LOCATION.set(0);
    }

    /** Returns a defensive copy of the coverage map for analysis tooling. */
    public static byte[] snapshot() {
        return COVERAGE_MAP.clone();
    }

    /** Returns the number of entries with a non-zero execution count. */
    public static int nonZeroCount() {
        int count = 0;
        for (byte value : COVERAGE_MAP) {
            if (value != 0) {
                count++;
            }
        }
        return count;
    }

    private static void notifyNewEdge(int edgeId) {
	System.out.println("new edge: " + String.valueOf(edgeId));
        for (CoverageEventListener listener : LISTENERS) {
            try {
                listener.onNewEdge(edgeId);
            } catch (RuntimeException e) {
                System.err.println("Coverage listener failed: " + e);
            }
        }
    }
}
