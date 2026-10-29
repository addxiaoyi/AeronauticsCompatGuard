package dev.aeronautics.compatguard.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

public final class CompatibilityServerState {
    public static final int MAX_WORK_PER_TICK = 32;
    public static final int MAX_QUEUED_WORK = 4096;
    public static final int SNAPSHOT_RETENTION = 3;

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
    private long tick;
    private long rejectedOffThread;
    private long quarantineCount;
    private long completedWork;
    private long lastTickNanos;
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
        saveReservations();
        work.clear();
        pendingBlockEntityCleanup.clear();
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
        if (tickStartNanos != 0L && lastTickNanos - tickStartNanos > 50_000_000L) {
            slowTicks++;
        }
        if (tick % 1200L == 0L) {
            reportRetainedUnloadedChunks();
            reportEntityChurn();
        }
        processBlockEntityCleanup();
    }

    public void beginTick() {
        tickStartNanos = System.nanoTime();
    }

    public void enqueue(Runnable task) {
        if (server.isSameThread()) {
            enqueueOnServer(task);
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
        if (pendingBlockEntityCleanup.size() >= 4096) {
            pendingBlockEntityCleanup.removeFirst();
        }
        pendingBlockEntityCleanup.addLast(new PendingBlockEntityCleanup(
                new WeakReference<>(level), pos.immutable(), id, rawData));
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
        try {
            Files.createDirectories(dir);
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
            Files.writeString(manifest, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to write quarantine event", error);
        }
    }

    private static final int MAX_QUARANTINE_RAW_CHARS = 262_144;

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
        Reservation current = reservations.get(requestId);
        if (current != null) {
            if (!current.itemId().equals(itemId) || current.amount() != amount) {
                throw new IllegalStateException("Request UUID was reused for a different item reservation");
            }
            return current;
        }
        Reservation reservation = new Reservation(requestId, itemId, amount, ReservationState.RESERVED, now, 0, 0L);
        reservations.put(requestId, reservation);
        if (!saveReservations()) {
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
        if (saveReservations()) {
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
        updateReservation(requestId, ReservationState.DELIVERED, 0);
        if (!saveReservations()) {
            reservations.put(requestId, current);
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
            if (!saveReservations()) {
                reservations.put(requestId, current);
            }
            return;
        }
        long delay = Math.min(1200L, 20L << retries);
        reservations.put(requestId, new Reservation(
                current.requestId(), current.itemId(), current.amount(),
                ReservationState.RETRY, current.createdAt(), retries, tick + delay));
        if (!saveReservations()) {
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
        if (saveReservations()) {
            return true;
        }
        reservations.put(requestId, current);
        return false;
    }

    public void release(UUID requestId) {
        requireServerThread();
        Reservation current = reservations.remove(requestId);
        if (current != null && !saveReservations()) {
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
        if (saveReservations()) {
            return true;
        }
        reservations.put(requestId, new Reservation(
                reservation.requestId(), reservation.itemId(), reservation.amount(),
                ReservationState.IN_FLIGHT, reservation.createdAt(), reservation.retries(), 0L));
        return false;
    }

    public Status status() {
        return new Status(
                tick,
                work.size(),
                reservations.size(),
                quarantineCount,
                completedWork,
                rejectedOffThread,
                lastTickNanos,
                slowTicks,
                unloadedChunkProbes.size());
    }

    public void onChunkUnloaded(ServerLevel level, ChunkAccess chunk) {
        requireServerThread();
        if (unloadedChunkProbes.size() >= 4096) {
            unloadedChunkProbes.removeFirst();
        }
        ChunkPos pos = chunk.getPos();
        unloadedChunkProbes.addLast(new UnloadedChunkProbe(
                new WeakReference<>(chunk),
                level.dimension().location().toString(),
                pos.x,
                pos.z,
                tick));
    }

    public void trackEntityJoin(Entity entity) {
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
        trackedEntities.entrySet().removeIf(entry -> entry.getValue().entity().get() == null);
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
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path guardRoot = worldRoot.resolve("aeronauticscompatguard");
        Path snapshots = guardRoot.resolve("snapshots");
        Path staging = snapshots.resolve(".staging-" + UUID.randomUUID());
        try {
            Files.createDirectories(snapshots);
            if (!server.saveAllChunks(true, true, true)) {
                LOGGER.error("[CompatGuard] Server save failed; refusing automatic world cleanup");
                return;
            }
            Files.createDirectories(staging);
            Files.walkFileTree(worldRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (directory.equals(guardRoot)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(staging.resolve(worldRoot.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Path target = staging.resolve(worldRoot.relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
            String name = "startup-" + Instant.now().toString().replace(':', '-') + ".json";
            Map<String, Object> manifest = new HashMap<>();
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("minecraftVersion", "1.21.1");
            manifest.put("neoForgeVersion", "21.1.250");
            manifest.put("dimensions", StreamSupport.stream(server.getAllLevels().spliterator(), false)
                    .map(level -> level.dimension().location().toString()).sorted().toList());
            manifest.put("players", server.getPlayerList().getPlayerCount());
            manifest.put("snapshotType", "full-world-root-copy");
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
            LOGGER.info("[CompatGuard] Full world snapshot created at {}", complete);
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Could not create full rolling world snapshot", error);
            deleteTree(staging);
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

    private Path stateFile() {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("aeronauticscompatguard")
                .resolve("reservations.json");
    }

    private void loadReservations() {
        Path file = stateFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Reservation[] saved = JSON.fromJson(Files.readString(file), Reservation[].class);
            reservations.clear();
            int unresolved = 0;
            if (saved != null) {
                for (Reservation reservation : saved) {
                    if (reservation != null && reservation.requestId() != null) {
                        reservations.put(reservation.requestId(), reservation);
                        if (reservation.state() == ReservationState.IN_FLIGHT) {
                            unresolved++;
                        }
                    }
                }
            }
            if (unresolved > 0) {
                LOGGER.error("[CompatGuard] {} inventory transactions were in-flight at shutdown; "
                        + "automatic replay is disabled to avoid item duplication", unresolved);
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.error("[CompatGuard] Failed to load reservation ledger", error);
        }
    }

    private boolean saveReservations() {
        Path target = stateFile();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, JSON.toJson(reservations.values()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

    public enum ReservationState { PENDING, RESERVED, IN_FLIGHT, DELIVERED, RETRY, CANCELLED }

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
                         long rejectedOffThread, long lastTickNanos, long slowTicks, int trackedUnloadedChunks) {}

    private record UnloadedChunkProbe(
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
            String rawData
    ) {
        private String location(ServerLevel loadedLevel) {
            return loadedLevel.dimension().location() + " " + pos.toShortString();
        }
    }
}
