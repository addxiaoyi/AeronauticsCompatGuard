package dev.aeronautics.compatguard.compat;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class CompatibilityPatchRegistry {
    public static final String MINECOLONIES_CREATE_HANDLER = "minecolonies.create.handler_refresh";
    public static final String SABLE_IRIS_VEIL_BOOL_UNIFORMS = "sable.iris.veil.bool_uniforms";
    public static final String CRATERLIB_CONFIG_IDEMPOTENCY = "craterlib.simplerpc.config_idempotency";
    public static final String MINECRAFT_REMOVED_ENTITY_TRACKER = "minecraft.removed_entity_tracker";
    public static final String MINECRAFT_INVALID_ITEM_STACK = "minecraft.invalid_item_stack";
    public static final String MINECRAFT_UNKNOWN_ENTITY_DATA = "minecraft.unknown_entity_data";
    public static final String NEOFORGE_UNKNOWN_ATTACHMENT = "neoforge.unknown_attachment";
    public static final String MINECRAFT_RIDING_MOVEMENT_CORRECTION = "minecraft.riding_movement_correction";
    public static final String LOOT_TABLE_PARSE_GUARD = "minecraft.loot_table_parse_guard";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Boolean> ACTIVATION = new ConcurrentHashMap<>();
    private static volatile boolean statusLogged;
    private static final Map<String, Adapter> ADAPTERS = List.of(
                    new Adapter(
                            MINECOLONIES_CREATE_HANDLER,
                            "server inventory capability refresh",
                            List.of(
                                    new ModRequirement("minecolonies", "~1.1.1405"),
                                    new ModRequirement("create", "~6.0.10")),
                            "com.minecolonies.core.tileentities.TileEntityColonyBuilding",
                            "getItemHandlerCap",
                            List.of("net.minecraft.core.Direction")),
                    new Adapter(
                            SABLE_IRIS_VEIL_BOOL_UNIFORMS,
                            "client shader boolean uniforms",
                            List.of(
                                    new ModRequirement("sable", "~2.0.5"),
                                    new ModRequirement("iris", "~1.8.14"),
                                    new ModRequirement("veil", "~4.3.2")),
                            "net.minecraft.client.renderer.ShaderInstance",
                            "updateLocations",
                            List.of()),
                    new Adapter(
                            CRATERLIB_CONFIG_IDEMPOTENCY,
                            "SimpleRPC duplicate config registration",
                            List.of(
                                    new ModRequirement("craterlib", "~3.1.3"),
                                    new ModRequirement("simplerpc", "~4.1.4")),
                            "com.hypherionmc.craterlib.core.config.ConfigController",
                            "register_config",
                            List.of("com.hypherionmc.craterlib.core.config.AbstractConfig")),
                    new Adapter(
                            MINECRAFT_REMOVED_ENTITY_TRACKER,
                            "server removed-entity pairing guard",
                            List.of(),
                            "net.minecraft.server.level.ServerEntity",
                            "sendPairingData",
                            List.of(
                                    "net.minecraft.server.level.ServerPlayer",
                                    "net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor")),
                    new Adapter(
                            MINECRAFT_INVALID_ITEM_STACK,
                            "invalid item stack quarantine",
                            List.of(),
                            "net.minecraft.world.item.ItemStack",
                            "parse",
                            List.of(
                                    "net.minecraft.core.HolderLookup$Provider",
                                    "net.minecraft.nbt.Tag")),
                    new Adapter(
                            MINECRAFT_UNKNOWN_ENTITY_DATA,
                            "unknown entity save quarantine",
                            List.of(),
                            "net.minecraft.world.entity.EntityType",
                            "loadEntityRecursive",
                            List.of(
                                    "net.minecraft.nbt.CompoundTag",
                                    "net.minecraft.world.level.Level",
                                    "java.util.function.Function")),
                    new Adapter(
                            LOOT_TABLE_PARSE_GUARD,
                            "loot table parse guard",
                            List.of(),
                            "net.minecraft.world.level.storage.loot.LootDataType",
                            "deserialize",
                            List.of("net.minecraft.resources.ResourceLocation",
                                    "com.mojang.serialization.DynamicOps",
                                    "java.lang.Object")),
                    new Adapter(
                            MINECRAFT_RIDING_MOVEMENT_CORRECTION,
                            "server riding movement correction",
                            List.of(),
                            "net.minecraft.server.network.ServerGamePacketListenerImpl",
                            "handleMovePlayer",
                            List.of("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket")),
                    new Adapter(
                            NEOFORGE_UNKNOWN_ATTACHMENT,
                            "unknown data attachment quarantine",
                            List.of(),
                            "net.neoforged.neoforge.attachment.AttachmentHolder",
                            "deserializeAttachments",
                            List.of(
                                    "net.minecraft.core.HolderLookup$Provider",
                                    "net.minecraft.nbt.CompoundTag")))
            .stream()
            .collect(Collectors.toUnmodifiableMap(Adapter::id, adapter -> adapter));

    private CompatibilityPatchRegistry() {}

    public static boolean isEnabled(String adapterId) {
        Adapter adapter = ADAPTERS.get(adapterId);
        if (adapter == null) {
            return false;
        }
        return statusLogged
                ? ACTIVATION.computeIfAbsent(adapterId, ignored -> inspect(adapter))
                : inspect(adapter);
    }

    public static void logEnabledAdapters() {
        for (Adapter adapter : ADAPTERS.values()) {
            String reason = activationFailure(adapter);
            if (reason == null) {
                String versions = adapter.mods().stream()
                        .sorted((left, right) -> left.modId().compareTo(right.modId()))
                        .map(requirement -> requirement.modId() + " " + modVersion(requirement.modId()))
                        .collect(Collectors.joining(", "));
                ACTIVATION.put(adapter.id(), true);
                LOGGER.info("[CompatGuard] Enabled {} for {} ({})", adapter.id(), versions, adapter.scope());
                continue;
            }
            ACTIVATION.put(adapter.id(), false);
            LOGGER.info("[CompatGuard] Skipped {}: {}", adapter.id(), reason);
        }
        statusLogged = true;
    }

    private static boolean inspect(Adapter adapter) {
        return activationFailure(adapter) == null;
    }

    private static String activationFailure(Adapter adapter) {
        for (ModRequirement requirement : adapter.mods()) {
            String currentVersion = modVersion(requirement.modId());
            if (currentVersion == null) {
                return "missing mod " + requirement.modId();
            }
            if (!requirement.spec().matches(currentVersion)) {
                return "untested version " + requirement.modId() + " " + currentVersion
                        + " (gate " + requirement.spec() + ")";
            }
        }

        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> owner = Class.forName(adapter.targetClass(), false, loader);
            Class<?>[] parameterTypes = adapter.parameterTypes().stream()
                    .map(type -> loadClass(type, loader))
                    .toArray(Class<?>[]::new);
            findTargetMethod(owner, adapter.targetMethod(), parameterTypes);
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            return "target API unavailable: " + adapter.targetClass() + "#" + adapter.targetMethod();
        }
    }

    private static void findTargetMethod(Class<?> owner, String name, Class<?>[] parameters)
            throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            try {
                current.getDeclaredMethod(name, parameters);
                return;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name);
    }

    private static Class<?> loadClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Missing target API parameter " + name, error);
        }
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
    }

    private record ModRequirement(String modId, VersionSpec spec) {
        private ModRequirement(String modId, String spec) {
            this(modId, VersionSpec.parse(spec));
        }
    }

    private record Adapter(
            String id,
            String scope,
            List<ModRequirement> mods,
            String targetClass,
            String targetMethod,
            List<String> parameterTypes
    ) {}
}
