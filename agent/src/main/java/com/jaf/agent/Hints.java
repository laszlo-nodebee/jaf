package com.jaf.agent;

/**
 * Simple helper that logs when certain interesting string hashes are observed.
 */
public final class Hints {
    private static final String MESSAGE_PREFIX =
            "[JAF Hints] Observed String.equals value with target hash: ";
    private static final int[] TARGET_HASHES = {
        "demo-secret".hashCode()
    };

    private Hints() {}

    public static void onEquals(String left, Object right) {
        if (right instanceof String) {
            check(left);
            check((String) right);
        }
    }

    private static void check(String value) {
        if (value == null) {
            return;
        }
        int hash = value.hashCode();
        for (int target : TARGET_HASHES) {
            if (hash == target) {
                logMatch(value, hash);
                return;
            }
        }
    }

    private static void logMatch(String value, int hash) {
        System.out.println(MESSAGE_PREFIX + value + " (hash=" + hash + ")");
    }
}
