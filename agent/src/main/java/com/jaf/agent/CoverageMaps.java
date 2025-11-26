package com.jaf.agent;

final class CoverageMaps {
    private CoverageMaps() {}

    static boolean hasNewCoverage(byte[] traceBitmap) {
        if (traceBitmap == null) {
            return false;
        }
        byte[] global = CoverageRuntime.globalCoverageMap();
        int length = Math.min(traceBitmap.length, global.length);
        for (int i = 0; i < length; i++) {
            if (traceBitmap[i] != 0 && global[i] == 0) {
                return true;
            }
        }
        return false;
    }

    static void mergeIntoGlobal(byte[] traceBitmap) {
        if (traceBitmap == null) {
            return;
        }
        byte[] global = CoverageRuntime.globalCoverageMap();
        int length = Math.min(traceBitmap.length, global.length);
        for (int i = 0; i < length; i++) {
            int traceValue = Byte.toUnsignedInt(traceBitmap[i]);
            if (traceValue == 0) {
                continue;
            }
            int globalValue = Byte.toUnsignedInt(global[i]);
            int merged = globalValue + traceValue;
            if (merged > 0xFF) {
                merged = 0xFF;
            }
            global[i] = (byte) merged;
        }
    }
}
