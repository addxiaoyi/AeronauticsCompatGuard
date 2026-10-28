package dev.aeronautics.compatguard.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

public final class CompatibilityServerState {
    public static final int MAX_WORK_PER_TICK = 32;
    public static final int SNAPSHOT_RETENTION = 3;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Map<MinecraftServer, CompatibilityServerState> STATES = new HashMap<>();

    private final MinecraftServer server;
    private final Deque<Runnable> work = new ArrayDeque<>();
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final Deque<UnloadedChunkProbe> unloadedChunkProbes = new ArrayDeque<>();
    private final AtomicBoolean snapshotCreated = new AtomicBoolean();
    private long tick;
    private long rejectedOffThread;
    private long quarantineCount;
    private long completedWork;
    private long lastTickNanos;
    private long tickStartNanos;
    private long slowTicks;

    private CompatibilityServerState(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized CompatibilityServerState get(MinecraftServer server) {
        return STATES.computeIfAbsent(server, CompatibilityServerState::new);
    }

    public static synchronized void remove(MinecraftServer server) {
        STATES.remove(server);
    }

    public static boolean recordOnActiveServer(String kind, String id, String location, String rawData) {
        CompatibilityServerState active;
        synchronized (CompatibilityServerState.class) {
            active = STATES.values().stream().findFirst().orElse(null);
        }
        if (active == null) {
            LOGGER.warn("[CompatGuard] Quarantined {} without an active server: {} at {}", kind, id, location);
            return false;
        }
        active.enqueue(() -> active.recordQuarantine(kind, id, location, rawData));
        return true;
    }

    public void onServerStarted() {
        loadReservations();
        createRollingSnapshot();
        LOGGER.info("[CompatGuard] Server guard started for {}", server.getWorldPath(LevelResource.ROOT));
    }

    public void onServerStopped() {
        saveReservations();
        work.clear();
    }

    public void tick() {
        tick++;
        int budget = MAX_WORK_PER_TICK;
        while (budget-- > 0) {
            Runnable task = work.pollFirst();
            if (task == null) {
                break;
            }
            task.run();
            completedWork++;
        }
        lastTickNanos = System.nanoTime();
        if (tickStartNanos != 0L && lastTickNanos - tickStartNanos > 50_000_000L) {
            slowTicks++;
        }
        if (tick % 1200L == 0L) {
            reportRetainedUnloadedChunks();
        }
    }

    public void beginTick() {
        tickStartNanos = System.nanoTime();
    }

    public void enqueue(Runnable task) {
        if (server.isSameThread()) {
            work.addLast(task);
            return;
        }
        rejectedOffThread++;
        server.execute(() -> work.addLast(task));
    }

    public void recordQuarantine(String kind, String id, String location, String reason) {
        quarantineCount++;
        Path dir = server.getWorldPath(LevelResource.ROOT)
                .resolve("aeronauticscompatguard")
                .resolve("quarantine");
        try {
            Files.createDirectories(dir);
            Path manifest = dir.resolve("events.jsonl");
            String json = JSON.toJson(Map.of(
                    "time", Instant.now().toString(),
                    "kind", kind,
                    "id", id,
                    "location", location,
                    "reason", reason));
            Files.writeString(manifest, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to write quarantine event", error);
        }
    }

    public Reservation reserve(UUID requestId, String itemId, int amount, long now) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Reservations must be mutated on the server thread");
        }
        if (amount <= 0 || itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Reservation requires a positive amount and item id");
        }
        Reservation current = reservations.get(requestId);
        if (current != null) {
            return current;
        }
        Reservation reservation = new Reservation(requestId, itemId, amount, ReservationState.RESERVED, now, 0);
        reservations.put(requestId, reservation);
        return reservation;
    }

    public void markInFlight(UUID requestId) {
        requireServerThread();
        updateReservation(requestId, ReservationState.IN_FLIGHT, 0);
        saveReservations();
    }

    public void markDelivered(UUID requestId) {
        requireServerThread();
        updateReservation(requestId, ReservationState.DELIVERED, 0);
        saveReservations();
    }

    public void scheduleRetry(UUID requestId) {
        requireServerThread();
        Reservation current = reservations.get(requestId);
        if (current == null) {
            return;
        }
        int retries = current.retries() + 1;
        if (retries > 8) {
            updateReservation(requestId, ReservationState.CANCELLED, retries);
            saveReservations();
            return;
        }
        updateReservation(requestId, ReservationState.RETRY, retries);
        saveReservations();
    }

    public void release(UUID requestId) {
        requireServerThread();
        reservations.remove(requestId);
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
                    reservation.retries()));
        }
        saveReservations();
        return true;
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
                    current.createdAt(), retries == 0 ? current.retries() : retries));
        }
    }

    private void createRollingSnapshot() {
        if (!snapshotCreated.compareAndSet(false, true)) {
            return;
        }
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("aeronauticscompatguard");
        Path snapshots = root.resolve("snapshots");
        try {
            Files.createDirectories(snapshots);
            String name = "startup-" + Instant.now().toString().replace(':', '-') + ".json";
            Map<String, Object> manifest = new HashMap<>();
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("minecraftVersion", "1.21.1");
            manifest.put("neoForgeVersion", "21.1.250");
            manifest.put("dimensions", StreamSupport.stream(server.getAllLevels().spliterator(), false)
                    .map(level -> level.dimension().location().toString()).sorted().toList());
            manifest.put("players", server.getPlayerList().getPlayerCount());
            manifest.put("note", "Metadata marker only; take a full world backup before cleanup.");
            Files.writeString(snapshots.resolve(name), JSON.toJson(manifest));
            List<Path> entries;
            try (var stream = Files.list(snapshots)) {
                entries = stream.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path entry : entries.subList(Math.min(SNAPSHOT_RETENTION, entries.size()), entries.size())) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Could not create rolling snapshot marker", error);
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
            if (saved != null) {
                for (Reservation reservation : saved) {
                    if (reservation != null && reservation.requestId() != null) {
                        reservations.put(reservation.requestId(), reservation);
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.error("[CompatGuard] Failed to load reservation ledger", error);
        }
    }

    private void saveReservations() {
        try {
            Files.createDirectories(stateFile().getParent());
            Files.writeString(stateFile(), JSON.toJson(reservations.values()));
        } catch (IOException error) {
            LOGGER.error("[CompatGuard] Failed to save reservation ledger", error);
        }
    }

    public enum ReservationState { PENDING, RESERVED, IN_FLIGHT, DELIVERED, RETRY, CANCELLED }

    public record Reservation(UUID requestId, String itemId, int amount, ReservationState state, long createdAt, int retries) {}

    public record Status(long tick, int queuedWork, int reservations, long quarantined, long completedWork,
                         long rejectedOffThread, long lastTickNanos, long slowTicks, int trackedUnloadedChunks) {}

    private record UnloadedChunkProbe(
            WeakReference<ChunkAccess> chunk,
            String dimension,
            int x,
            int z,
            long unloadedAtTick
    ) {}
}
