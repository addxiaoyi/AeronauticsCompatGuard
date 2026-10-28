package dev.aeronautics.compatguard.compat;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class CompatibilityPatchRegistry {
    public static final String MINECOLONIES_CREATE_HANDLER = "minecolonies.create.handler_refresh";
    public static final String SABLE_IRIS_VEIL_BOOL_UNIFORMS = "sable.iris.veil.bool_uniforms";
    public static final String CRATERLIB_CONFIG_IDEMPOTENCY = "craterlib.simplerpc.config_idempotency";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Boolean> ACTIVATION = new ConcurrentHashMap<>();
    private static volatile boolean statusLogged;
    private static final Map<String, Adapter> ADAPTERS = List.of(
                    new Adapter(
                            MINECOLONIES_CREATE_HANDLER,
                            "server inventory capability refresh",
                            List.of(
                                    new ModRequirement("minecolonies", Set.of("1.1.1405-1.21.1-snapshot")),
                                    new ModRequirement("create", Set.of("6.0.10"))),
                            "com.minecolonies.core.tileentities.TileEntityColonyBuilding",
                            "getItemHandlerCap",
                            List.of("net.minecraft.core.Direction")),
                    new Adapter(
                            SABLE_IRIS_VEIL_BOOL_UNIFORMS,
                            "client shader boolean uniforms",
                            List.of(
                                    new ModRequirement("sable", Set.of("2.0.5")),
                                    new ModRequirement("iris", Set.of("1.8.14-beta.1+mc1.21.1")),
                                    new ModRequirement("veil", Set.of("4.3.2"))),
                            "net.minecraft.client.renderer.ShaderInstance",
                            "updateLocations",
                            List.of()),
                    new Adapter(
                            CRATERLIB_CONFIG_IDEMPOTENCY,
                            "SimpleRPC duplicate config registration",
                            List.of(
                                    new ModRequirement("craterlib", Set.of("3.1.3")),
                                    new ModRequirement("simplerpc", Set.of("4.1.4"))),
                            "com.hypherionmc.craterlib.core.config.ConfigController",
                            "register_config",
                            List.of("com.hypherionmc.craterlib.core.config.AbstractConfig")))
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
            if (!requirement.testedVersions().contains(currentVersion)) {
                return "untested version " + requirement.modId() + " " + currentVersion;
            }
        }

        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> owner = Class.forName(adapter.targetClass(), false, loader);
            Class<?>[] parameterTypes = adapter.parameterTypes().stream()
                    .map(type -> loadClass(type, loader))
                    .toArray(Class<?>[]::new);
            owner.getMethod(adapter.targetMethod(), parameterTypes);
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            return "target API unavailable: " + adapter.targetClass() + "#" + adapter.targetMethod();
        }
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

    private record ModRequirement(String modId, Set<String> testedVersions) {}

    private record Adapter(
            String id,
            String scope,
            List<ModRequirement> mods,
            String targetClass,
            String targetMethod,
            List<String> parameterTypes
    ) {}
}
