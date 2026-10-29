package dev.aeronautics.compatguard.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.aeronautics.compatguard.CompatGuardConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

public final class CompatibilityServerState {
    public static final int MAX_WORK_PER_TICK = 32;
    public static final int MAX_QUEUED_WORK = 4096;
    public static final int SNAPSHOT_RETENTION = 3;
    /** Completed request ids remembered so a finished UUID cannot start a second delivery. */
    public static final int COMPLETION_TOMBSTONES = 4096;
    /** Journal entries accepted before the ledger is compacted back into one snapshot file. */
    public static final int MAX_JOURNAL_ENTRIES = 256;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Map<MinecraftServer, CompatibilityServerState> STATES = new HashMap<>();
    private static final Deque<PendingQuarantine> PRESTART_QUARANTINE = new ArrayDeque<>();
    private static final int MAX_PRESTART_QUARANTINE = 128;

    private final MinecraftServer server;
    private final Deque<Runnable> work = new ArrayDeque<>();
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final Deque<UnloadedChunkProbe> unloadedChunkProbes = new ArrayDeque<>();
    private final Deque<PendingBlockEntityCleanup> pendingBlockEntityCleanup = new ArrayDeque<>();
    private final Map<UUID, EntityProbe> trackedEntities = new HashMap<>();
    private final Map<String, EntityChurn> entityChurn = new HashMap<>();
    private final AtomicBoolean snapshotCreated = new AtomicBoolean();
    private final Map<UUID, Runnable> retryActions = new HashMap<>();
    private final Deque<UUID> completedRequests = new ArrayDeque<>();
    private final Set<UUID> completedRequestIds = new HashSet<>();
    private final Set<String> pendingBlockEntityKeys = new HashSet<>();
    private final Set<String> unloadedChunkProbeKeys = new HashSet<>();
    private final ConcurrentLinkedQueue<QuarantineWrite> quarantineOutbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger quarantineOutboxSize = new AtomicInteger();
    private final AtomicBoolean quarantineFlushPending = new AtomicBoolean();
    private long deliveredCount;
    private int journalEntries;
    private BufferedWriter journalWriter;
    private long tick;
    private long rejectedOffThread;
    private long quarantineCount;
    private long completedWork;
    private long lastTickNanos;
    private long lastTickDurationNanos;
    private long tickStartNanos;
    private long slowTicks;
    private volatile boolean snapshotReady;

    private CompatibilityServerState(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized CompatibilityServerState get(MinecraftServer server) {
        return STATES.computeIfAbsent(server, CompatibilityServerState::new);
    }

    public static synchronized void remove(MinecraftServer server) {
        STATES.remove(server);
    }

    public static boolean recordOnActiveServer(
            String kind,
            String id,
            String location,
            String reason,
            String rawData
    ) {
        CompatibilityServerState active;
        synchronized (CompatibilityServerState.class) {
            active = STATES.values().stream().findFirst().orElse(null);
        }
        if (active == null) {
            synchronized (PRESTART_QUARANTINE) {
                if (PRESTART_QUARANTINE.size() >= MAX_PRESTART_QUARANTINE) {
                    PRESTART_QUARANTINE.removeFirst();
                    LOGGER.warn("[CompatGuard] Prestart quarantine buffer full; oldest record dropped");
                }
                PRESTART_QUARANTINE.addLast(new PendingQuarantine(kind, id, location, reason, rawData));
            }
            return true;
        }
        active.enqueue(() -> active.recordQuarantine(kind, id, location, reason, rawData));
        return true;
    }

    public void onServerStarted() {
        loadReservations();
        createRollingSnapshot();
        flushPrestartQuarantine();
        if (!snapshotReady) {
            LOGGER.error("[CompatGuard] Automatic cleanup is disabled because the startup snapshot failed");
        }
        LOGGER.info("[CompatGuard] Server guard started for {}", server.getWorldPath(LevelResource.ROOT));
    }

    public void onServerStopped() {
        compactLedger();
        flushQuarantineOutbox();
        work.clear();
        pendingBlockEntityCleanup.clear();
        pendingBlockEntityKeys.clear();
        retryActions.clear();
    }

    public void tick() {
        tick++;
        int budget = MAX_WORK_PER_TICK;
        while (budget-- > 0) {
            Runnable task = work.pollFirst();
            if (task == null) {
                break;
            }
            try {
                task.run();
                completedWork++;
            } catch (RuntimeException error) {
                LOGGER.error("[CompatGuard] Deferred compatibility operation failed", error);
            }
        }
        lastTickNanos = System.nanoTime();
        lastTickDurationNanos = tickStartNanos == 0L ? 0L : lastTickNanos - tickStartNanos;
        if (lastTickDurationNanos > 50_000_000L) {
            slowTicks++;
        }
        if (tick % 1200L == 0L) {
            reportRetainedUnloadedChunks();
            reportEntityChurn();
        }
        processBlockEntityCleanup();
        runDueRetries();
    }

    public void beginTick() {
        tickStartNanos = System.nanoTime();
    }

    public void enqueue(Runnable task) {
        if (server.isSameThread()) {
            enqueueOnServer(task);
            return;
        }
        // Bound the queue before submitting: otherwise an off-thread flood allocates an unbounded
        // number of Runnable captures on the server executor before the size check can run.
        if (work.size() >= MAX_QUEUED_WORK) {
            rejectedOffThread++;
            LOGGER.warn("[CompatGuard] Work queue full; deferred compatibility operation rejected off-thread");
            return;
        }
        rejectedOffThread++;
        server.execute(() -> enqueueOnServer(task));
    }

    private void enqueueOnServer(Runnable task) {
        if (work.size() >= MAX_QUEUED_WORK) {
            rejectedOffThread++;
            LOGGER.warn("[CompatGuard] Work queue full; deferred compatibility operation rejected");
            return;
        }
        work.addLast(task);
    }

    public void queueAirBlockEntityCleanup(ServerLevel level, BlockPos pos, String id, String rawData) {
        requireServerThread();
        // Chunk reloads re-report the same block entities; without dedupe the queue fills with
        // duplicate scans of one position and the recorded "air block entity" events multiply.
        String key = level.dimension().location() + "|" + pos.asLong();
        if (!pendingBlockEntityKeys.add(key)) {
            return;
        }
        if (pendingBlockEntityCleanup.size() >= 4096) {
            PendingBlockEntityCleanup dropped = pendingBlockEntityCleanup.removeFirst();
            pendingBlockEntityKeys.remove(dropped.key());
        }
        pendingBlockEntityCleanup.addLast(new PendingBlockEntityCleanup(
                new WeakReference<>(level), pos.immutable(), id, rawData, key));
    }

    public void recordQuarantine(String kind, String id, String location, String reason) {
        recordQuarantine(kind, id, location, reason, "");
    }

    public void recordQuarantine(
            String kind,
            String id,
            String location,
            String reason,
            String rawData
    ) {
        quarantineCount++;
        Path dir = server.getWorldPath(LevelResource.ROOT)
                .resolve("aeronauticscompatguard")
                .resolve("quarantine");
        Path manifest = dir.resolve("events.jsonl");
        String source = rawData == null ? "" : rawData;
        int retainedLength = Math.min(source.length(), MAX_QUARANTINE_RAW_CHARS);
        String retained = source.substring(0, retainedLength);
        Map<String, Object> record = new HashMap<>();
        record.put("time", Instant.now().toString());
        record.put("kind", kind);
        record.put("id", id);
        record.put("location", location);
        record.put("reason", reason);
        record.put("rawData", retained);
        record.put("rawDataTruncated", retainedLength < source.length());
        record.put("rawDataSha256", sha256(source));
        String json = JSON.toJson(record);
        // The append is moved off the server thread: one Files.createDirectories + writeString per
        // event on the tick thread is a straight-line slow-tick source during migration storms.
        if (quarantineOutboxSize.incrementAndGet() > MAX_QUARANTINE_OUTBOX) {
            quarantineOutboxSize.decrementAndGet();
            LOGGER.warn("[CompatGuard] Quarantine outbox full; dropping event {}/{}", kind, id);
            return;
        }
        quarantineOutbox.add(new QuarantineWrite(manifest, json));
        scheduleQuarantineFlush();
    }

    private void scheduleQuarantineFlush() {
        if (quarantineFlushPending.compareAndSet(false, true)) {
            QUARANTINE_WRITER.execute(this::flushQuarantineBatch);
        }
    }

    private void flushQuarantineBatch() {
        int budget = MAX_QUARANTINE_WRITES_PER_FLUSH;
        try {
            while (budget-- > 0) {
                QuarantineWrite write = quarantineOutbox.poll();
                if (write == null) {
                    return;
                }
                quarantineOutboxSize.decrementAndGet();
                appendQuarantineLine(write);
            }
        } finally {
            quarantineFlushPending.set(false);
            if (!quarantineOutbox.isEmpty()) {
                scheduleQuarantineFlush();
            }
        }
    }

    private void flushQuarantineOutbox() {
        QuarantineWrite write;
        while ((write = quarantineOutbox.poll()) != null) {
            quarantineOutboxSize.decrementAndGet();
            appendQuarantineLine(write);
        }
    }

    private static void appendQuarantineLine(QuarantineWrite write) {
        try {
            Files.createDirectories(write.manifest().getParent());
            Files.writeString(write.manifest(), write.json() + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to write quarantine event", error);
        }
    }

    private record QuarantineWrite(Path manifest, String json) {}

    private static final int MAX_QUARANTINE_RAW_CHARS = 262_144;
    private static final int MAX_QUARANTINE_OUTBOX = 4096;
    private static final int MAX_QUARANTINE_WRITES_PER_FLUSH = 256;
    private static final int MAX_RETRIES_PER_TICK = 8;
    private static final ExecutorService QUARANTINE_WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CompatGuard-QuarantineWriter");
        thread.setDaemon(true);
        return thread;
    });

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", error);
        }
    }

    public Reservation reserve(UUID requestId, String itemId, int amount, long now) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Reservations must be mutated on the server thread");
        }
        if (requestId == null || amount <= 0 || itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Reservation requires a positive amount and item id");
        }
        if (completedRequestIds.contains(requestId)) {
            throw new IllegalStateException("Request UUID already completed and must not be reserved again");
        }
        Reservation current = reservations.get(requestId);
        if (current != null) {
            if (!current.itemId().equals(itemId) || current.amount() != amount) {
                throw new IllegalStateException("Request UUID was reused for a different item reservation");
            }
            if (current.state() != ReservationState.RESERVED && current.state() != ReservationState.RETRY) {
                throw new IllegalStateException(
                        "Request UUID was reused after the reservation reached " + current.state());
            }
            return current;
        }
        Reservation reservation = new Reservation(requestId, itemId, amount, ReservationState.RESERVED, now, 0, 0L);
        reservations.put(requestId, reservation);
        if (!record("put", requestId)) {
            reservations.remove(requestId);
            throw new IllegalStateException("Could not persist item reservation");
        }
        return reservation;
    }

    public boolean beginInFlight(UUID requestId, String itemId, int amount) {
        requireServerThread();
        Reservation current = reservations.get(requestId);
        if (current == null
                || current.state() != ReservationState.RESERVED
                || !current.itemId().equals(itemId)
                || amount <= 0
                || amount != current.amount()) {
            return false;
        }
        reservations.put(requestId, new Reservation(
                current.requestId(), current.itemId(), amount, ReservationState.IN_FLIGHT,
                current.createdAt(), current.retries(), 0L));
        if (record("put", requestId)) {
            return true;
        }
        reservations.put(requestId, current);
        return false;
    }

    public void markDelivered(UUID requestId) {
        requireServerThread();
        Reservation current = reservations.get(requestId);
        if (current == null) {
            return;
        }
        // A delivered request must not stay in the ledger: a retained DELIVERED entry lets the same
        // UUID be reserved again by accident and grows the persisted file without bound.
        reservations.remove(requestId);
        retryActions.remove(requestId);
        rememberCompletion(requestId);
        if (record("remove", requestId)) {
            deliveredCount++;
            return;
        }
        reservations.put(requestId, current);
    }

    /**
     * Parks a reservation whose commit outcome is unknown (handler threw, ledger write failed).
     * SUSPECT is terminal for automatic machinery: it is never replayed, retried, or settled, so an
     * unknown outcome cannot turn into duplicated items. It is surfaced through {@code /acg status}.
     */
    public boolean markSuspect(UUID requestId, String itemId, String reason, String rawData) {
        requireServerThread();
        Reservation current = reservations.get(requestId);
        if (current == null) {
            recordQuarantine("inventory_transaction", requestId.toString(), "unknown", reason, rawData);
            return false;
        }
        reservations.put(requestId, new Reservation(
                current.requestId(), current.itemId(), current.amount(), ReservationState.SUSPECT,
                current.createdAt(), current.retries(), 0L));
        retryActions.remove(requestId);
        if (!record("put", requestId)) {
            LOGGER.error("[CompatGuard] Could not persist SUSPECT reservation {}; state is memory-only", requestId);
        }
        recordQuarantine("inventory_transaction", requestId.toString(), "unknown", reason, rawData);
        return true;
    }

    public boolean releaseSuspect(UUID requestId) {
        requireServerThread();
        Reservation current = reservations.get(requestId);
        if (current == null || current.state() != ReservationState.SUSPECT) {
            return false;
        }
        reservations.put(requestId, new Reservation(
                current.requestId(), current.itemId(), current.amount(), ReservationState.RESERVED,
                current.createdAt(), current.retries(), 0L));
        return record("put", requestId);
    }

    /**
     * Registers the deferred work that finishes a partially committed request.
     *
     * <p>The action must re-resolve its endpoint when it runs (block entity lookup, chunk check)
     * instead of capturing an {@code IItemHandler} or {@code IFluidHandler} instance: the chunk may
     * have unloaded and the handler may be stale by the time the backoff expires.</p>
     */
    /**
     * Keeps a bounded ring of finished request ids.
     *
     * <p>A settled request is removed from the ledger so the persisted file stays small, but that
     * also erased the only evidence that the UUID was already used. The runtime self test caught
     * exactly this: after a full settlement the same UUID could reserve again, which is how a
     * delivered order gets delivered twice. The tombstone ring keeps the duplicate protection while
     * keeping the ledger bounded.</p>
     */
    private void rememberCompletion(UUID requestId) {
        if (requestId == null || !completedRequestIds.add(requestId)) {
            return;
        }
        completedRequests.addLast(requestId);
        while (completedRequests.size() > COMPLETION_TOMBSTONES) {
            UUID evicted = completedRequests.removeFirst();
            completedRequestIds.remove(evicted);
        }
    }

    public int completionTombstones() {
        return completedRequestIds.size();
    }

    public boolean registerRetryAction(UUID requestId, Runnable action) {
        requireServerThread();
        if (requestId == null || action == null || retryActions.size() >= MAX_QUEUED_WORK) {
            return false;
        }
        retryActions.put(requestId, action);
        return true;
    }

    private void runDueRetries() {
        if (retryActions.isEmpty()) {
            return;
        }
        int budget = MAX_RETRIES_PER_TICK;
        var iterator = retryActions.entrySet().iterator();
        while (iterator.hasNext() && budget-- > 0) {
            Map.Entry<UUID, Runnable> entry = iterator.next();
            UUID requestId = entry.getKey();
            if (!reservations.containsKey(requestId)) {
                iterator.remove();
                continue;
            }
            if (!retryReady(requestId) || !claimRetry(requestId)) {
                continue;
            }
            try {
                entry.getValue().run();
            } catch (RuntimeException error) {
                LOGGER.error("[CompatGuard] Deferred inventory retry failed for {}", requestId, error);
            }
            iterator.remove();
        }
    }

    public void scheduleRetry(UUID requestId) {
        requireServerThread();
        Reservation current = reservations.get(requestId);
        if (current == null) {
            return;
        }
        int retries = current.retries() + 1;
        if (retries > 8) {
            reservations.put(requestId, new Reservation(
                    current.requestId(), current.itemId(), current.amount(),
                    ReservationState.CANCELLED, current.createdAt(), retries, 0L));
            if (!record("put", requestId)) {
                reservations.put(requestId, current);
            }
            return;
        }
        long delay = Math.min(1200L, 20L << retries);
        reservations.put(requestId, new Reservation(
                current.requestId(), current.itemId(), current.amount(),
                ReservationState.RETRY, current.createdAt(), retries, tick + delay));
        if (!record("put", requestId)) {
            reservations.put(requestId, current);
        }
    }

    public boolean retryReady(UUID requestId) {
        Reservation reservation = reservations.get(requestId);
        return reservation != null
                && reservation.state() == ReservationState.RETRY
                && tick >= reservation.nextAttemptTick();
    }

    public boolean claimRetry(UUID requestId) {
        requireServerThread();
        Reservation current = reservations.get(requestId);
        if (current == null || current.state() != ReservationState.RETRY
                || tick < current.nextAttemptTick()) {
            return false;
        }
        Reservation claimed = new Reservation(
                current.requestId(), current.itemId(), current.amount(),
                ReservationState.RESERVED, current.createdAt(), current.retries(), 0L);
        reservations.put(requestId, claimed);
        if (record("put", requestId)) {
            return true;
        }
        reservations.put(requestId, current);
        return false;
    }

    public void release(UUID requestId) {
        requireServerThread();
        Reservation current = reservations.remove(requestId);
        if (current != null && !record("remove", requestId)) {
            reservations.put(requestId, current);
        }
    }

    public boolean settleInFlight(UUID requestId, String itemId, int accepted) {
        requireServerThread();
        Reservation reservation = reservations.get(requestId);
        if (reservation == null
                || reservation.state() != ReservationState.IN_FLIGHT
                || !reservation.itemId().equals(itemId)
                || accepted < 0
                || accepted > reservation.amount()) {
            return false;
        }
        if (accepted == reservation.amount()) {
            reservations.remove(requestId);
            rememberCompletion(requestId);
        } else {
            reservations.put(requestId, new Reservation(
                    requestId,
                    itemId,
                    reservation.amount() - accepted,
                    ReservationState.RESERVED,
                    reservation.createdAt(),
                reservation.retries(),
                0L));
        }
        if (record(accepted == reservation.amount() ? "remove" : "put", requestId)) {
            return true;
        }
        reservations.put(requestId, new Reservation(
                reservation.requestId(), reservation.itemId(), reservation.amount(),
                ReservationState.IN_FLIGHT, reservation.createdAt(), reservation.retries(), 0L));
        return false;
    }

    public Status status() {
        long suspects = reservations.values().stream()
                .filter(value -> value.state() == ReservationState.SUSPECT)
                .count();
        return new Status(
                tick,
                work.size(),
                reservations.size(),
                quarantineCount,
                completedWork,
                rejectedOffThread,
                lastTickDurationNanos,
                slowTicks,
                unloadedChunkProbes.size(),
                suspects,
                deliveredCount,
                retryActions.size(),
                quarantineOutboxSize.get());
    }

    public void onChunkUnloaded(ServerLevel level, ChunkAccess chunk) {
        if (!CompatGuardConfig.flag(CompatGuardConfig.CHUNK_RETENTION_PROBES, false)) {
            return;
        }
        requireServerThread();
        ChunkPos pos = chunk.getPos();
        String key = level.dimension().location() + "|" + pos.toLong();
        if (!unloadedChunkProbeKeys.add(key)) {
            return;
        }
        if (unloadedChunkProbes.size() >= 4096) {
            UnloadedChunkProbe dropped = unloadedChunkProbes.removeFirst();
            unloadedChunkProbeKeys.remove(dropped.key());
        }
        unloadedChunkProbes.addLast(new UnloadedChunkProbe(
                key,
                new WeakReference<>(chunk),
                level.dimension().location().toString(),
                pos.x,
                pos.z,
                tick));
    }

    public void trackEntityJoin(Entity entity) {
        if (!CompatGuardConfig.flag(CompatGuardConfig.ENTITY_CHURN_TRACKING, false)) {
            return;
        }
        requireServerThread();
        UUID id = entity.getUUID();
        EntityProbe previous = trackedEntities.get(id);
        Entity previousEntity = previous == null ? null : previous.entity().get();
        if (previousEntity != null && previousEntity != entity && !previousEntity.isRemoved()) {
            LOGGER.warn("[CompatGuard] Duplicate live entity UUID {} for {} at {} and {}",
                    id, previous.typeId(), previous.location(), entity.blockPosition().toShortString());
        }
        if (trackedEntities.size() >= 100_000) {
            pruneTrackedEntities();
        }
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String source = typeId.substring(0, typeId.indexOf(':'));
        String trackedId = typeId;
        if (entity instanceof ItemEntity itemEntity) {
            trackedId = BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem()).toString();
            source = trackedId.substring(0, trackedId.indexOf(':'));
        }
        String location = entity.blockPosition().toShortString();
        trackedEntities.put(id, new EntityProbe(new WeakReference<>(entity), source, trackedId, location, tick));
        EntityChurn churn = entityChurn.computeIfAbsent(source + "|" + trackedId, ignored -> new EntityChurn());
        churn.spawned++;
        churn.lastSeenTick = tick;
    }

    public void trackEntityLeave(Entity entity) {
        if (!CompatGuardConfig.flag(CompatGuardConfig.ENTITY_CHURN_TRACKING, false)) {
            return;
        }
        requireServerThread();
        EntityProbe probe = trackedEntities.remove(entity.getUUID());
        if (probe == null) {
            return;
        }
        EntityChurn churn = entityChurn.computeIfAbsent(
                probe.source() + "|" + probe.typeId(), ignored -> new EntityChurn());
        churn.removed++;
        churn.lastSeenTick = tick;
        if (entity instanceof ItemEntity && tick - probe.joinedTick() <= 2) {
            churn.shortLived++;
        }
    }

    private void reportRetainedUnloadedChunks() {
        long cutoff = tick - 1200L;
        long retained = 0;
        Map<String, Integer> byDimension = new HashMap<>();
        for (var iterator = unloadedChunkProbes.iterator(); iterator.hasNext();) {
            UnloadedChunkProbe probe = iterator.next();
            if (probe.unloadedAtTick() > cutoff) {
                continue;
            }
            if (probe.chunk().get() == null) {
                unloadedChunkProbeKeys.remove(probe.key());
                iterator.remove();
                continue;
            }
            retained++;
            byDimension.merge(probe.dimension(), 1, Integer::sum);
        }
        if (retained > 0) {
            LOGGER.warn("[CompatGuard] {} unloaded chunk objects remain strongly reachable after 60 seconds: {}",
                    retained, byDimension);
        }
    }

    private void reportEntityChurn() {
        pruneTrackedEntities();
        List<Map.Entry<String, EntityChurn>> noisy = entityChurn.entrySet().stream()
                .filter(entry -> entry.getValue().spawned + entry.getValue().removed >= 100)
                .sorted((left, right) -> Long.compare(
                        right.getValue().shortLived, left.getValue().shortLived))
                .limit(10)
                .toList();
        if (!noisy.isEmpty()) {
            LOGGER.warn("[CompatGuard] High entity churn (source|type=spawned/removed/short-lived): {}",
                    noisy.stream().map(entry -> entry.getKey() + "=" + entry.getValue().spawned + "/"
                            + entry.getValue().removed + "/" + entry.getValue().shortLived).toList());
        }
        entityChurn.entrySet().removeIf(entry -> tick - entry.getValue().lastSeenTick > 24_000L);
    }

    private void pruneTrackedEntities() {
        // Dropping only null weak references kept one entry per player for the whole session: a
        // player who logged out has a removed (but still collectable-later) entity object, and the
        // probe map never shrank below its high-water mark.
        trackedEntities.entrySet().removeIf(entry -> {
            Entity tracked = entry.getValue().entity().get();
            return tracked == null || tracked.isRemoved();
        });
    }

    private void flushPrestartQuarantine() {
        List<PendingQuarantine> pending;
        synchronized (PRESTART_QUARANTINE) {
            pending = List.copyOf(PRESTART_QUARANTINE);
            PRESTART_QUARANTINE.clear();
        }
        for (PendingQuarantine entry : pending) {
            recordQuarantine(entry.kind(), entry.id(), entry.location(), entry.reason(), entry.rawData());
        }
    }

    private void processBlockEntityCleanup() {
        int budget = 4;
        while (budget-- > 0 && !pendingBlockEntityCleanup.isEmpty()) {
            PendingBlockEntityCleanup pending = pendingBlockEntityCleanup.removeFirst();
            pendingBlockEntityKeys.remove(pending.key());
            ServerLevel level = pending.level().get();
            if (level == null || !level.hasChunkAt(pending.pos())) {
                continue;
            }
            LevelChunk chunk = level.getChunkAt(pending.pos());
            boolean hasSavedEntity = chunk.getBlockEntityNbt(pending.pos()) != null
                    || chunk.getBlockEntities().containsKey(pending.pos());
            if (!level.getBlockState(pending.pos()).isAir() || !hasSavedEntity) {
                continue;
            }
            if (!snapshotReady) {
                recordQuarantine("block_entity", pending.id(), pending.location(level),
                        "air block entity retained because startup snapshot is unavailable", pending.rawData());
                continue;
            }
            recordQuarantine("block_entity", pending.id(), pending.location(level),
                    "block state is air", pending.rawData());
            chunk.removeBlockEntity(pending.pos());
        }
    }

    private void saveCompletedRequests() {
        Path target = stateFile().resolveSibling("completed.json");
        Path temporary = target.resolveSibling("completed.json.tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary,
                    JSON.toJson(completedRequests.stream().map(UUID::toString).toList()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to save completed request tombstones", error);
        }
    }

    private void loadCompletedRequests() {
        Path file = stateFile().resolveSibling("completed.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String[] ids = JSON.fromJson(Files.readString(file), String[].class);
            if (ids != null) {
                for (String id : ids) {
                    if (id == null) {
                        continue;
                    }
                    try {
                        rememberCompletion(UUID.fromString(id));
                    } catch (IllegalArgumentException ignored) {
                        // A malformed entry is not worth failing startup over.
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.error("[CompatGuard] Failed to load completed request tombstones", error);
        }
    }

    /** Direct ledger lookup: the transaction path must not copy the whole ledger per operation. */
    public Reservation reservation(UUID requestId) {
        return requestId == null ? null : reservations.get(requestId);
    }

    public List<Reservation> reservations() {
        return List.copyOf(reservations.values());
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Reservation state must be mutated on the server thread");
        }
    }

    private void updateReservation(UUID requestId, ReservationState state, int retries) {
        Reservation current = reservations.get(requestId);
        if (current != null) {
            reservations.put(requestId, new Reservation(
                    current.requestId(), current.itemId(), current.amount(), state,
                    current.createdAt(), retries == 0 ? current.retries() : retries,
                    state == ReservationState.RETRY ? current.nextAttemptTick() : 0L));
        }
    }

    private void createRollingSnapshot() {
        if (!snapshotCreated.compareAndSet(false, true)) {
            return;
        }
        if (!CompatGuardConfig.flag(CompatGuardConfig.STARTUP_INTEGRITY_INDEX, true)) {
            LOGGER.warn("[CompatGuard] Startup integrity index is disabled; automatic air block entity "
                    + "cleanup stays disabled because it has no baseline to fall back on");
            return;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path guardRoot = worldRoot.resolve("aeronauticscompatguard");
        Path snapshots = guardRoot.resolve("snapshots");
        long maxHashedBytes = CompatGuardConfig.number(
                CompatGuardConfig.STARTUP_INDEX_MAX_HASHED_BYTES, (int) MAX_HASHED_SNAPSHOT_FILE_BYTES);
        Path staging = snapshots.resolve(".staging-" + UUID.randomUUID());
        try {
            Files.createDirectories(snapshots);
            if (!server.saveAllChunks(true, true, true)) {
                LOGGER.error("[CompatGuard] Server save failed; refusing automatic world cleanup");
                return;
            }
            Files.createDirectories(staging);
            // Previously this copied the whole world root three times over. That stalls server start
            // on multi-gigabyte worlds and triples disk usage for a guard whose job is auditing. The
            // snapshot keeps the same gate (snapshotReady) but stores an integrity index instead of a
            // duplicate world: path, size, mtime and a hash for the small critical files.
            Path indexPath = staging.resolve("index.jsonl");
            try (var writer = Files.newBufferedWriter(indexPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                List<String> lines = new ArrayList<>();
                Files.walkFileTree(worldRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        if (directory.equals(guardRoot)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("path", worldRoot.relativize(file).toString().replace('\\', '/'));
                        entry.put("size", attributes.size());
                        entry.put("modified", attributes.lastModifiedTime().toMillis());
                        if (attributes.size() <= maxHashedBytes) {
                            entry.put("sha256", sha256File(file));
                        }
                        lines.add(JSON.toJson(entry));
                        return FileVisitResult.CONTINUE;
                    }
                });
                for (String line : lines) {
                    writer.write(line);
                    writer.write(System.lineSeparator());
                }
            }
            String name = "startup-" + Instant.now().toString().replace(':', '-') + ".json";
            Map<String, Object> manifest = new HashMap<>();
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("minecraftVersion", "1.21.1");
            manifest.put("neoForgeVersion", "21.1.250");
            manifest.put("dimensions", StreamSupport.stream(server.getAllLevels().spliterator(), false)
                    .map(level -> level.dimension().location().toString()).sorted().toList());
            manifest.put("players", server.getPlayerList().getPlayerCount());
            manifest.put("snapshotType", "integrity-index");
            manifest.put("indexFile", "index.jsonl");
            Files.writeString(staging.resolve("snapshot.json"), JSON.toJson(manifest));
            String stamp = name.substring("startup-".length(), name.length() - ".json".length());
            Path complete = snapshots.resolve("world-" + stamp);
            Files.move(staging, complete, StandardCopyOption.ATOMIC_MOVE);
            List<Path> entries;
            try (var stream = Files.list(snapshots)) {
                entries = stream
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("world-"))
                        .sorted(Comparator.comparingLong(CompatibilityServerState::lastModified).reversed())
                        .toList();
            }
            for (Path entry : entries.subList(Math.min(SNAPSHOT_RETENTION, entries.size()), entries.size())) {
                deleteTree(entry);
            }
            snapshotReady = true;
            LOGGER.info("[CompatGuard] Startup integrity index created at {}", complete);
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Could not create full rolling world snapshot", error);
            deleteTree(staging);
        }
    }

    private static final long MAX_HASHED_SNAPSHOT_FILE_BYTES = 8L * 1024L * 1024L;

    private static String sha256File(Path file) {
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException error) {
            return Long.MIN_VALUE;
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Could not remove snapshot directory {}", root, error);
        }
    }

    private Path journalFile() {
        return stateFile().resolveSibling("reservations.journal");
    }

    /**
     * Appends one state transition instead of rewriting the whole ledger.
     *
     * <p>Measured on NeoForge 21.1.250 with `/acg bench`: a full snapshot rewrite costs ~4.4 ms per
     * call on the server thread (JSON serialise + atomic move on Windows), and every transition used
     * to pay it. An append of a single small line is the same durability for a process crash and
     * costs orders of magnitude less; the snapshot is rebuilt when the journal reaches
     * {@link #MAX_JOURNAL_ENTRIES} entries and on shutdown.</p>
     */
    private boolean record(String op, UUID requestId) {
        requireServerThread();
        Map<String, Object> entry = new HashMap<>();
        entry.put("op", op);
        entry.put("id", requestId == null ? null : requestId.toString());
        Reservation current = requestId == null ? null : reservations.get(requestId);
        if (current != null) {
            entry.put("reservation", current);
        }
        try {
            BufferedWriter writer = journalWriter();
            writer.write(JSON.toJson(entry));
            writer.write(System.lineSeparator());
            writer.flush();
            if (++journalEntries >= MAX_JOURNAL_ENTRIES) {
                compactLedger();
            }
            return true;
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to append ledger transition; falling back to a full write", error);
            closeJournal();
            return saveReservations();
        }
    }

    private BufferedWriter journalWriter() throws IOException {
        if (journalWriter == null) {
            Files.createDirectories(stateFile().getParent());
            journalWriter = Files.newBufferedWriter(journalFile(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        }
        return journalWriter;
    }

    private void closeJournal() {
        if (journalWriter == null) {
            return;
        }
        try {
            journalWriter.close();
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to close the ledger journal", error);
        }
        journalWriter = null;
    }

    private void replayJournal() {
        Path journal = journalFile();
        if (!Files.isRegularFile(journal)) {
            return;
        }
        try (var lines = Files.lines(journal, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    Map<String, Object> entry = JSON.fromJson(line, Map.class);
                    Object idValue = entry.get("id");
                    if (idValue == null) {
                        return;
                    }
                    UUID id = UUID.fromString(idValue.toString());
                    Object reservation = entry.get("reservation");
                    if (reservation == null) {
                        reservations.remove(id);
                    } else {
                        reservations.put(id, JSON.fromJson(JSON.toJsonTree(reservation), Reservation.class));
                    }
                } catch (RuntimeException malformed) {
                    LOGGER.warn("[CompatGuard] Skipping malformed ledger journal entry", malformed);
                }
            });
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to replay the ledger journal", error);
        }
    }

    /** Rewrites the snapshot atomically and drops the journal. Also runs on shutdown. */
    private void compactLedger() {
        closeJournal();
        if (!saveReservations()) {
            return;
        }
        try {
            Files.deleteIfExists(journalFile());
            journalEntries = 0;
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to clear the ledger journal after compaction", error);
        }
    }

    private Path stateFile() {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("aeronauticscompatguard")
                .resolve("reservations.json");
    }

    private void loadReservations() {
        Path file = stateFile();
        if (!Files.isRegularFile(file)) {
            // A crash right after the first journal append leaves no snapshot yet; replay must not be
            // skipped just because the snapshot file does not exist.
            replayJournal();
            compactLedger();
            loadCompletedRequests();
            return;
        }
        try {
            Reservation[] saved = JSON.fromJson(Files.readString(file), Reservation[].class);
            reservations.clear();
            int unresolved = 0;
            if (saved != null) {
                for (Reservation reservation : saved) {
                    if (reservation == null || reservation.requestId() == null || reservation.state() == null) {
                        continue;
                    }
                    if (reservation.state() == ReservationState.IN_FLIGHT) {
                        // The process died mid-transaction: the commit outcome is unknown. Parking the
                        // entry in SUSPECT keeps it visible and non-replayable. Leaving it IN_FLIGHT
                        // made it unreachable, because settleInFlight/beginInFlight both require a
                        // different state, so such a request could never finish or be released.
                        reservations.put(reservation.requestId(), new Reservation(
                                reservation.requestId(), reservation.itemId(), reservation.amount(),
                                ReservationState.SUSPECT, reservation.createdAt(),
                                reservation.retries(), 0L));
                        unresolved++;
                        continue;
                    }
                    if (reservation.state() == ReservationState.CANCELLED) {
                        continue;
                    }
                    reservations.put(reservation.requestId(), new Reservation(
                            reservation.requestId(), reservation.itemId(), reservation.amount(),
                            reservation.state(), reservation.createdAt(), reservation.retries(),
                            reservation.state() == ReservationState.RETRY ? tick + retryDelay(reservation.retries()) : 0L));
                }
            }
            if (unresolved > 0) {
                LOGGER.error("[CompatGuard] {} inventory transactions were in-flight at shutdown; "
                        + "they are parked as SUSPECT for operator reconciliation instead of being replayed", unresolved);
            }
            if (unresolved > 0 && !saveReservations()) {
                LOGGER.error("[CompatGuard] Could not persist the recovered reservation ledger");
            }
            replayJournal();
            compactLedger();
            loadCompletedRequests();
        } catch (IOException | RuntimeException error) {
            LOGGER.error("[CompatGuard] Failed to load reservation ledger", error);
        }
    }

    private static long retryDelay(int retries) {
        return Math.min(1200L, 20L << Math.max(0, Math.min(6, retries)));
    }


    private boolean saveReservations() {
        Path target = stateFile();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, JSON.toJson(reservations.values()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            saveCompletedRequests();
            return true;
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to save reservation ledger", error);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            return false;
        }
    }

    public enum ReservationState { PENDING, RESERVED, IN_FLIGHT, DELIVERED, RETRY, CANCELLED, SUSPECT }

    public record Reservation(
            UUID requestId,
            String itemId,
            int amount,
            ReservationState state,
            long createdAt,
            int retries,
            long nextAttemptTick
    ) {}

    public record Status(long tick, int queuedWork, int reservations, long quarantined, long completedWork,
                         long rejectedOffThread, long lastTickNanos, long slowTicks, int trackedUnloadedChunks,
                         long suspects, long delivered, int pendingRetries, int queuedQuarantine) {}

    private record UnloadedChunkProbe(
            String key,
            WeakReference<ChunkAccess> chunk,
            String dimension,
            int x,
            int z,
            long unloadedAtTick
    ) {}

    private record EntityProbe(
            WeakReference<Entity> entity,
            String source,
            String typeId,
            String location,
            long joinedTick
    ) {}

    private static final class EntityChurn {
        private long spawned;
        private long removed;
        private long shortLived;
        private long lastSeenTick;
    }

    private record PendingQuarantine(String kind, String id, String location, String reason, String rawData) {}

    private record PendingBlockEntityCleanup(
            WeakReference<ServerLevel> level,
            BlockPos pos,
            String id,
            String rawData,
            String key
    ) {
        private String location(ServerLevel loadedLevel) {
            return loadedLevel.dimension().location() + " " + pos.toShortString();
        }
    }
}
