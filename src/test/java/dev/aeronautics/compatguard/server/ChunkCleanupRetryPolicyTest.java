package dev.aeronautics.compatguard.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCleanupRetryPolicyTest {
    @Test
    void retriesAreBounded() {
        assertTrue(ChunkCleanupRetryPolicy.canRetry(0));
        assertTrue(ChunkCleanupRetryPolicy.canRetry(7));
        assertFalse(ChunkCleanupRetryPolicy.canRetry(8));
        assertFalse(ChunkCleanupRetryPolicy.canRetry(-1));
    }

    @Test
    void retryDelayGrowsAndIsCapped() {
        long first = ChunkCleanupRetryPolicy.nextAttemptTick(100, 0);
        long second = ChunkCleanupRetryPolicy.nextAttemptTick(first, 1);
        long last = ChunkCleanupRetryPolicy.nextAttemptTick(100, 7);

        assertEquals(140, first);
        assertTrue(second > first);
        assertEquals(1300, last);
    }

    @Test
    void exhaustedRetryHasNoFutureAttempt() {
        assertEquals(Long.MAX_VALUE, ChunkCleanupRetryPolicy.nextAttemptTick(500, 8));
    }
}
