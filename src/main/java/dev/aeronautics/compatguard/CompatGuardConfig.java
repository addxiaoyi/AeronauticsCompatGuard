package dev.aeronautics.compatguard;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Runtime switches for the guard layer.
 *
 * <p>The observation paths are not free: per-entity probes allocate a record and touch the entity
 * registry on every join, chunk retention probes keep a weak reference per unload, and the startup
 * index walks the world root. They default to off so a pack that installs this mod for the safety
 * fixes does not pay for diagnostics it never reads.</p>
 */
public final class CompatGuardConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<Boolean> ENTITY_CHURN_TRACKING;
    public static final ModConfigSpec.ConfigValue<Boolean> CHUNK_RETENTION_PROBES;
    public static final ModConfigSpec.ConfigValue<Boolean> PLAYER_RETENTION_PROBES;
    public static final ModConfigSpec.ConfigValue<Boolean> AIR_BLOCK_ENTITY_SCAN;
    public static final ModConfigSpec.ConfigValue<Boolean> STARTUP_INTEGRITY_INDEX;
    public static final ModConfigSpec.ConfigValue<Integer> STARTUP_INDEX_MAX_HASHED_BYTES;
    public static final ModConfigSpec.ConfigValue<Boolean> TRANSACTIONS_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("diagnostics");
        ENTITY_CHURN_TRACKING = builder
                .comment("Track per-entity join/leave probes and report high churn every 1200 ticks.",
                        "Costs one probe record per entity join; keep off unless investigating churn.")
                .define("entityChurnTracking", false);
        CHUNK_RETENTION_PROBES = builder
                .comment("Keep a weak reference per unloaded chunk to detect retained chunk objects.",
                        "Costs one queue entry per chunk unload (bounded to 4096).")
                .define("chunkRetentionProbes", false);
        PLAYER_RETENTION_PROBES = builder
                .comment("Keep a weak reference per logout to detect ServerPlayer objects that stay reachable.",
                        "Enable when a leak reporter (e.g. AllTheLeaks) shows retained ServerPlayer instances;",
                        "each retained player keeps its ServerLevel and therefore its chunks alive.")
                .define("playerRetentionProbes", false);
        builder.pop();

        builder.push("safety");
        AIR_BLOCK_ENTITY_SCAN = builder
                .comment("On chunk load, queue block entities whose block state is air for quarantine.",
                        "Runs one getBlockState per block entity position of every loaded chunk.")
                .define("airBlockEntityScan", true);
        STARTUP_INTEGRITY_INDEX = builder
                .comment("Write a startup integrity index (path/size/mtime/sha256) per server start.",
                        "When disabled, automatic air block entity cleanup stays disabled because it",
                        "relies on this baseline.")
                .define("startupIntegrityIndex", true);
        STARTUP_INDEX_MAX_HASHED_BYTES = builder
                .comment("Only files up to this size get a SHA-256 in the integrity index.")
                .defineInRange("startupIndexMaxHashedBytes", 8 * 1024 * 1024, 0, 512 * 1024 * 1024);
        builder.pop();

        builder.push("transactions");
        TRANSACTIONS_ENABLED = builder
                .comment("Enable the reservation-backed item and fluid transaction primitives.")
                .define("enabled", true);
        builder.pop();

        SPEC = builder.build();
    }

    private CompatGuardConfig() {}

    /** Reads a flag defensively: on a side where the config is not loaded yet this returns the default. */
    public static boolean flag(ModConfigSpec.ConfigValue<Boolean> value, boolean fallback) {
        try {
            Boolean read = value.get();
            return read == null ? fallback : read;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    public static int number(ModConfigSpec.ConfigValue<Integer> value, int fallback) {
        try {
            Integer read = value.get();
            return read == null ? fallback : read;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }
}
