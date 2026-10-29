package dev.aeronautics.compatguard.server;

import dev.aeronautics.compatguard.CompatGuardConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Measures the guard layer's own per-operation cost so the config defaults can be justified with
 * numbers instead of intuition.
 */
public final class CompatGuardBench {
    private CompatGuardBench() {}

    public static List<String> run(CompatibilityServerState state) {
        List<String> lines = new ArrayList<>();
        lines.add("config: entityChurnTracking="
                + CompatGuardConfig.flag(CompatGuardConfig.ENTITY_CHURN_TRACKING, false)
                + " chunkRetentionProbes="
                + CompatGuardConfig.flag(CompatGuardConfig.CHUNK_RETENTION_PROBES, false)
                + " airBlockEntityScan="
                + CompatGuardConfig.flag(CompatGuardConfig.AIR_BLOCK_ENTITY_SCAN, true)
                + " startupIntegrityIndex="
                + CompatGuardConfig.flag(CompatGuardConfig.STARTUP_INTEGRITY_INDEX, true)
                + " transactions="
                + CompatGuardConfig.flag(CompatGuardConfig.TRANSACTIONS_ENABLED, true));

        // tick(): the per-tick entry point that every server pay for.
        lines.add("tick() " + measure(2000, state::tick));

        // status(): what /acg status allocates on demand.
        lines.add("status() " + measure(20000, state::status));

        // Ledger lookup: the path a transaction hits before touching any inventory.
        UUID missing = UUID.randomUUID();
        lines.add("reservation() miss " + measure(200000, () -> state.reservation(missing)));

        // Whole-ledger copy, for comparison: this is what the hot path used to do per operation.
        lines.add("reservations() copy " + measure(20000, state::reservations));

        // Scaling check: the whole-ledger copy is O(n) by construction, so measure it with a
        // populated ledger instead of an empty one.
        List<UUID> temp = new ArrayList<>();
        int target = 200;
        long reserveStart = System.nanoTime();
        for (int index = 0; index < target; index++) {
            UUID id = UUID.randomUUID();
            state.reserve(id, "minecraft:stone", 1, 0L);
            temp.add(id);
        }
        long reserveElapsed = System.nanoTime() - reserveStart;
        lines.add(String.format("reserve() %.1f us/op over %d ops (append-only journal)",
                (double) reserveElapsed / 1000.0 / target, target));
        lines.add("reservations() copy @n=" + target + " " + measure(20000, state::reservations));
        UUID present = temp.get(temp.size() / 2);
        lines.add("reservation() hit @n=" + target + " " + measure(200000, () -> state.reservation(present)));
        for (UUID id : temp) {
            state.release(id);
        }

        lines.add("ledger entries=" + state.status().reservations()
                + " tombstones=" + state.completionTombstones()
                + " queuedQuarantine=" + state.status().queuedQuarantine());
        return lines;
    }

    private static String measure(int iterations, Runnable action) {
        for (int warmup = 0; warmup < Math.min(iterations, 500); warmup++) {
            action.run();
        }
        long start = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            action.run();
        }
        long elapsed = System.nanoTime() - start;
        double perCall = (double) elapsed / iterations;
        return String.format("%.1f ns/op over %d ops", perCall, iterations);
    }
}
