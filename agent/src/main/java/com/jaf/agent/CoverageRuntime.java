package com.jaf.agent;

import java.util.Arrays;

/**
 * Coverage runtime modeled after AFL's edge coverage scheme.
 *
 * <p>Instrumented code calls {@link #enterEdge(int)} at the beginning of each basic block. When
 * tracing is enabled for the current thread, the runtime tracks a rolling previous-location value
 * per thread and bumps a byte-sized counter in the thread-local coverage bitmap using
 * {@code prev ^ cur} as the index.</p>
 */
public final class CoverageRuntime {
    static final int MAP_SIZE = 1 << 16; // 64K entries, must stay a power of two.

    private static final byte[] GLOBAL_COVERAGE_MAP = new byte[MAP_SIZE];
    private static final ThreadLocal<TraceState> TRACE_STATE = new ThreadLocal<>();

    private CoverageRuntime() {}

    /**
     * Records the execution of an edge identified by the provided block identifier.
     *
     * <p>If tracing is not active for the current thread, the call is ignored.</p>
     *
     * @param currentLocation pre-hashed block identifier (must already fit into {@link #MAP_SIZE})
     */
    public static void enterEdge(int currentLocation) {
        TraceState state = TRACE_STATE.get();
        if (state == null || !state.isActive()) {
            return;
        }

        int prev = state.previousLocation;
        int index = (prev ^ currentLocation) & (MAP_SIZE - 1);
        state.recordHit(index);
        state.previousLocation = (currentLocation >>> 1) & (MAP_SIZE - 1);
    }

    /**
     * Enables tracing for the current thread. When tracing transitions from inactive to active, the
     * per-thread coverage bitmap is cleared.
     */
    public static void startTracing() {
        TraceState state = TRACE_STATE.get();
        if (state == null) {
            state = new TraceState();
            TRACE_STATE.set(state);
        }
        state.start();
    }

    /**
     * Disables tracing for the current thread. When this call closes the outermost tracing scope,
     * the trace bitmap collected for the scope is returned. Inner scopes yield {@code null} to
     * signal that tracing is still active.
     *
     * @return the completed trace bitmap, or {@code null} if tracing remains active
     */
    public static byte[] stopTracing() {
        TraceState state = TRACE_STATE.get();
        if (state == null) {
            return null;
        }
        if (!state.stop()) {
            return null;
        }
        TRACE_STATE.remove();
        return state.bitmap;
    }

    /** Returns the trace bitmap for the current thread without modifying tracing state. */
    public static byte[] currentTraceBitmap() {
        TraceState state = TRACE_STATE.get();
        if (state == null || !state.isActive()) {
            return null;
        }
        return state.bitmap;
    }

    /** Returns whether tracing is currently active for the calling thread. */
    public static boolean isTracingActive() {
        TraceState state = TRACE_STATE.get();
        return state != null && state.isActive();
    }

    /** Resets the global coverage map and clears any per-thread tracing state. */
    public static void reset() {
        Arrays.fill(GLOBAL_COVERAGE_MAP, (byte) 0);
        TRACE_STATE.remove();
    }

    /** Returns a defensive copy of the global coverage map for analysis tooling. */
    public static byte[] snapshot() {
        return GLOBAL_COVERAGE_MAP.clone();
    }

    /** Returns the number of entries in the global coverage map with a non-zero execution count. */
    public static int nonZeroCount() {
        int count = 0;
        for (byte value : GLOBAL_COVERAGE_MAP) {
            if (value != 0) {
                count++;
            }
        }
        return count;
    }

    static byte[] globalCoverageMap() {
        return GLOBAL_COVERAGE_MAP;
    }

    private static final class TraceState {
        private final byte[] bitmap = new byte[MAP_SIZE];
        private int previousLocation = 0;
        private int depth = 0;

        void start() {
            if (depth == 0) {
                Arrays.fill(bitmap, (byte) 0);
                previousLocation = 0;
            }
            depth++;
        }

        boolean stop() {
            if (depth == 0) {
                return false;
            }
            depth--;
            if (depth == 0) {
                previousLocation = 0;
                return true;
            }
            return false;
        }

        boolean isActive() {
            return depth > 0;
        }

        void recordHit(int index) {
            byte value = bitmap[index];
            if (value != (byte) 0xFF) {
                bitmap[index] = (byte) (value + 1);
            }
        }
    }
}
