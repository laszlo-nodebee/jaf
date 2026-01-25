package com.jaf.fuzzer.coverage;

import java.util.BitSet;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Immutable wrapper around an AFL-style coverage bitmap.
 * Non-zero bytes represent executed edge indices.
 */
public final class CoverageBitmap {
    private static final CoverageBitmap EMPTY = new CoverageBitmap(new byte[0], false);
    private final byte[] data;

    private CoverageBitmap(byte[] data, boolean copy) {
        this.data = copy ? data.clone() : data;
    }

    public static CoverageBitmap empty() {
        return EMPTY;
    }

    public static CoverageBitmap fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return EMPTY;
        }
        return new CoverageBitmap(bytes, true);
    }

    public static CoverageBitmap fromIndices(int... indices) {
        if (indices == null || indices.length == 0) {
            return EMPTY;
        }
        int max = -1;
        for (int index : indices) {
            if (index > max) {
                max = index;
            }
        }
        if (max < 0) {
            return EMPTY;
        }
        byte[] bytes = new byte[max + 1];
        for (int index : indices) {
            if (index >= 0 && index < bytes.length) {
                bytes[index] = 1;
            }
        }
        return new CoverageBitmap(bytes, false);
    }

    public int length() {
        return data.length;
    }

    public byte[] toByteArray() {
        return data.clone();
    }

    public boolean isEmpty() {
        for (byte value : data) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    public int countNonZero() {
        int count = 0;
        for (byte value : data) {
            if (value != 0) {
                count++;
            }
        }
        return count;
    }

    public boolean isSubsetOf(CoverageBitmap other) {
        Objects.requireNonNull(other, "other");
        int otherLength = other.data.length;
        for (int i = 0; i < data.length; i++) {
            if (data[i] != 0 && (i >= otherLength || other.data[i] == 0)) {
                return false;
            }
        }
        return true;
    }

    public boolean covers(CoverageBitmap other) {
        Objects.requireNonNull(other, "other");
        return other.isSubsetOf(this);
    }

    public CoverageBitmap union(CoverageBitmap other) {
        Objects.requireNonNull(other, "other");
        int length = Math.max(data.length, other.data.length);
        if (length == 0) {
            return EMPTY;
        }
        byte[] merged = new byte[length];
        if (data.length > 0) {
            System.arraycopy(data, 0, merged, 0, data.length);
        }
        for (int i = 0; i < other.data.length; i++) {
            if (other.data[i] != 0 && merged[i] == 0) {
                merged[i] = other.data[i];
            }
        }
        return new CoverageBitmap(merged, false);
    }

    public CoverageBitmap intersect(CoverageBitmap other) {
        Objects.requireNonNull(other, "other");
        int length = Math.min(data.length, other.data.length);
        if (length == 0) {
            return EMPTY;
        }
        byte[] intersection = new byte[length];
        for (int i = 0; i < length; i++) {
            if (data[i] != 0 && other.data[i] != 0) {
                intersection[i] = data[i];
            }
        }
        return new CoverageBitmap(intersection, false);
    }

    public CoverageBitmap minus(CoverageBitmap other) {
        Objects.requireNonNull(other, "other");
        if (data.length == 0) {
            return EMPTY;
        }
        byte[] diff = new byte[data.length];
        int otherLength = other.data.length;
        for (int i = 0; i < data.length; i++) {
            if (data[i] != 0 && (i >= otherLength || other.data[i] == 0)) {
                diff[i] = data[i];
            }
        }
        return new CoverageBitmap(diff, false);
    }

    public CoverageBitmap without(BitSet indices) {
        Objects.requireNonNull(indices, "indices");
        if (indices.isEmpty() || data.length == 0) {
            return this;
        }
        byte[] filtered = null;
        for (int idx = indices.nextSetBit(0); idx >= 0 && idx < data.length;
                idx = indices.nextSetBit(idx + 1)) {
            if (data[idx] != 0) {
                if (filtered == null) {
                    filtered = data.clone();
                }
                filtered[idx] = 0;
            }
        }
        if (filtered == null) {
            return this;
        }
        return new CoverageBitmap(filtered, false);
    }

    public void forEachSetIndex(IntConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int i = 0; i < data.length; i++) {
            if (data[i] != 0) {
                consumer.accept(i);
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CoverageBitmap bitmap)) {
            return false;
        }
        if (data.length != bitmap.data.length) {
            return false;
        }
        for (int i = 0; i < data.length; i++) {
            if (data[i] != bitmap.data[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (byte value : data) {
            result = 31 * result + value;
        }
        return result;
    }
}
