package dev.aeronautics.compatguard;

import com.mojang.logging.LogUtils;
import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(AeronauticsCompatGuard.MOD_ID)
public final class AeronauticsCompatGuard {
    public static final String MOD_ID = "aeronauticscompatguard";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeronauticsCompatGuard(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, CompatGuardConfig.SPEC);
        LOGGER.info("Aeronautics Compat Guard initialized");
        CompatibilityPatchRegistry.logEnabledAdapters();
    }
}
