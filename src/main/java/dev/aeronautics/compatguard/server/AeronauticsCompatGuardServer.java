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
    public void onServerStopping(ServerStoppingEvent event) {
        CompatibilityServerState state = CompatibilityServerState.get(event.getServer());
        state.onServerStopped();
        CompatibilityServerState.remove(event.getServer());
    }
}
