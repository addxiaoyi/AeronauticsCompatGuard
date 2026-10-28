package dev.aeronautics.compatguard.server;

import dev.aeronautics.compatguard.AeronauticsCompatGuard;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = AeronauticsCompatGuard.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class AeronauticsCompatGuardServer {
    public AeronauticsCompatGuardServer() {
        AeronauticsCompatGuard.LOGGER.info("Dedicated-server diagnostics entry point initialized");
    }
}
