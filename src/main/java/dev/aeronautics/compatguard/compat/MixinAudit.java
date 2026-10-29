package dev.aeronautics.compatguard.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Says out loud whether each shipped mixin actually landed.
 *
 * <p>Every injector in this mod is declared with {@code require = 0} so a target signature change in
 * another mod cannot crash the game. The price of that safety is silence: an injector that no longer
 * matches simply does nothing, which is how the removed-entity guard stayed dead on NeoForge
 * 21.1.250 after {@code sendPairingData} gained a {@code PacketAndPayloadAcceptor} parameter. This
 * audit turns that silence into a startup line per mixin by looking for the {@code @Unique} marker
 * field each mixin adds to its target class.</p>
 */
public final class MixinAudit {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARKER = "aeronauticsCompatguard$applied";

    public enum Side { BOTH, DEDICATED_SERVER, CLIENT }

    public record Entry(String id, String targetClass, String method, Side side) {}

    /** Declared targets, shared with the conflict inventory so both reports cannot drift apart. */
    public record MixinTarget(String id, String className, String method) {}

    public static List<MixinTarget> targets() {
        List<MixinTarget> targets = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            targets.add(new MixinTarget(entry.id(), entry.targetClass(), entry.method()));
        }
        return targets;
    }

    public static boolean isOurTarget(String className) {
        for (Entry entry : ENTRIES) {
            if (entry.targetClass().equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("minecolonies.create.handler_refresh",
                    "com.minecolonies.core.tileentities.TileEntityColonyBuilding", "getItemHandlerCap", Side.BOTH),
            new Entry("craterlib.simplerpc.config_idempotency",
                    "com.hypherionmc.craterlib.core.config.ConfigController", "register_config", Side.BOTH),
            new Entry("minecraft.removed_entity_tracker",
                    "net.minecraft.server.level.ServerEntity", "sendPairingData", Side.BOTH),
            new Entry("minecraft.invalid_item_stack",
                    "net.minecraft.world.item.ItemStack", "parse", Side.BOTH),
            new Entry("minecraft.unknown_entity_data",
                    "net.minecraft.world.entity.EntityType", "loadEntityRecursive", Side.BOTH),
            new Entry("neoforge.unknown_attachment",
                    "net.neoforged.neoforge.attachment.AttachmentHolder", "deserializeAttachments", Side.BOTH),
            new Entry("sable.iris.veil.bool_uniforms",
                    "net.minecraft.client.renderer.ShaderInstance", "updateLocations", Side.CLIENT));

    private MixinAudit() {}

    public static List<String> report() {
        try {
            return buildReport();
        } catch (RuntimeException | LinkageError error) {
            LOGGER.error("[CompatGuard] Mixin audit failed; assuming the worst", error);
            return List.of("audit failed: " + error);
        }
    }

    private static List<String> buildReport() {
        boolean clientPresent = isClientPresent();
        List<String> lines = new ArrayList<>();
        int applied = 0;
        int missing = 0;
        for (Entry entry : ENTRIES) {
            String state;
            if (entry.side() == Side.CLIENT && !clientPresent) {
                state = "SKIPPED client-only mixin on a dedicated server";
            } else {
                Class<?> target;
                try {
                    target = Class.forName(entry.targetClass(), false,
                            Thread.currentThread().getContextClassLoader());
                } catch (ClassNotFoundException | LinkageError absent) {
                    state = "SKIPPED target mod not installed (" + entry.targetClass() + ")";
                    lines.add(entry.id() + " -> " + state);
                    continue;
                }
                boolean found = false;
                for (var field : target.getDeclaredFields()) {
                    if (field.getName().contains(MARKER)) {
                        found = true;
                        break;
                    }
                }
                state = found ? "APPLIED" : "MISSING: injector did not land on " + entry.targetClass();
                if (found) {
                    applied++;
                } else {
                    missing++;
                }
            }
            lines.add(entry.id() + " -> " + state);
        }
        String summary = "applied=" + applied + " missing=" + missing + " declared=" + ENTRIES.size();
        lines.add(summary);
        if (missing > 0) {
            LOGGER.warn("[CompatGuard] Mixin audit found {} injector(s) that did not apply; "
                    + "their guard is inactive. Run /acg mixins for the list.", missing);
        } else {
            LOGGER.info("[CompatGuard] Mixin audit: {}", summary);
        }
        return lines;
    }

    /**
     * Uses the loader's own dist flag. Probing {@code net.minecraft.client.Minecraft} instead crashes
     * a dedicated server: RuntimeDistCleaner throws rather than letting the class be missing, which
     * aborted ServerStartedEvent and took the server down with it.
     */
    private static boolean isClientPresent() {
        try {
            return net.neoforged.fml.loading.FMLEnvironment.dist
                    == net.neoforged.api.distmarker.Dist.CLIENT;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
}
