package dev.aeronautics.compatguard.server;

import dev.aeronautics.compatguard.AeronauticsCompatGuard;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import java.util.Set;
import net.neoforged.bus.api.SubscribeEvent;

@Mod(value = AeronauticsCompatGuard.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class AeronauticsCompatGuardServer {
    private static final Set<String> KNOWN_REMOVED_ENTITY_MODS = Set.of("alexsmobs", "guardvillagers");
    public AeronauticsCompatGuardServer() {
        AeronauticsCompatGuard.LOGGER.info("Dedicated-server diagnostics entry point initialized");
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(CompatibilityCommandRegistrar.class);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        CompatibilityServerState.get(event.getServer()).onServerStarted();
    }

    @SubscribeEvent
    public void onServerTickStart(ServerTickEvent.Pre event) {
        CompatibilityServerState.get(event.getServer()).beginTick();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        CompatibilityServerState.get(event.getServer()).tick();
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            CompatibilityServerState.get(level.getServer()).onChunkUnloaded(level, event.getChunk());
        }
    }

    @SubscribeEvent
    public void onEntityLoad(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        Entity entity = event.getEntity();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null || !KNOWN_REMOVED_ENTITY_MODS.contains(id.getNamespace())) {
            return;
        }
        if (!net.neoforged.fml.ModList.get().isLoaded(id.getNamespace())) {
            event.setCanceled(true);
            CompatibilityServerState.get(level.getServer()).recordQuarantine(
                    "entity", id.toString(), entity.blockPosition().toShortString(), "missing entity mod on disk load");
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        CompatibilityServerState state = CompatibilityServerState.get(level.getServer());
        for (BlockPos pos : event.getChunk().getBlockEntitiesPos()) {
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                continue;
            }
            state.recordQuarantine("block_entity", blockEntity.getType().toString(), pos.toShortString(), "block state is air");
            level.removeBlockEntity(pos);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CompatibilityServerState state = CompatibilityServerState.get(event.getServer());
        state.onServerStopped();
        CompatibilityServerState.remove(event.getServer());
    }
}
