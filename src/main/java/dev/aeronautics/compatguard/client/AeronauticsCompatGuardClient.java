package dev.aeronautics.compatguard.client;

import dev.aeronautics.compatguard.AeronauticsCompatGuard;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = AeronauticsCompatGuard.MOD_ID, dist = Dist.CLIENT)
public final class AeronauticsCompatGuardClient {
    public AeronauticsCompatGuardClient() {
        AeronauticsCompatGuard.LOGGER.info("Client diagnostics entry point initialized");
    }
}
