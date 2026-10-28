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
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;

@Mod(value = AeronauticsCompatGuard.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class AeronauticsCompatGuardServer {
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
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        Entity entity = event.getEntity();
        CompatibilityServerState.get(level.getServer()).trackEntityJoin(entity);
    }

    @SubscribeEvent
    public void onEntityUnload(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            CompatibilityServerState.get(level.getServer()).trackEntityLeave(event.getEntity());
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
            net.minecraft.nbt.CompoundTag raw = event.getChunk().getBlockEntityNbt(pos);
            if (raw == null) {
                continue;
            }
            state.queueAirBlockEntityCleanup(
                    level,
                    pos,
                    raw.getString("id"),
                    raw.toString());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CompatibilityServerState state = CompatibilityServerState.get(event.getServer());
        state.onServerStopped();
        CompatibilityServerState.remove(event.getServer());
    }
}
