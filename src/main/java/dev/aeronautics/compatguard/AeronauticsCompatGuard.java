package dev.aeronautics.compatguard;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AeronauticsCompatGuard.MOD_ID)
public final class AeronauticsCompatGuard {
    public static final String MOD_ID = "aeronauticscompatguard";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeronauticsCompatGuard() {
        LOGGER.info("Aeronautics Compat Guard initialized");
    }
}
