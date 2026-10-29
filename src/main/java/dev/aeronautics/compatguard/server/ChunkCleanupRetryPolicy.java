package dev.aeronautics.compatguard.server;

/** Bounded retry policy for block-entity cleanup tasks whose chunk is temporarily unavailable. */
public final class ChunkCleanupRetryPolicy {
    public static final int MAX_ATTEMPTS = 8;
    private static final long MAX_DELAY_TICKS = 1200L;

    private ChunkCleanupRetryPolicy() {}

    public static boolean canRetry(int attempts) {
        return attempts >= 0 && attempts < MAX_ATTEMPTS;
    }

    public static long nextAttemptTick(long currentTick, int attempts) {
        if (!canRetry(attempts)) {
            return Long.MAX_VALUE;
        }
        long delay = Math.min(MAX_DELAY_TICKS, 20L << Math.min(attempts + 1, 6));
        return currentTick + delay;
    }
}
