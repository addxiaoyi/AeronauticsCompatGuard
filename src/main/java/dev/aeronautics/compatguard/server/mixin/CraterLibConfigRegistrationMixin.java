package dev.aeronautics.compatguard.server.mixin;

import com.mojang.logging.LogUtils;
import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Pseudo
@Mixin(targets = "com.hypherionmc.craterlib.core.config.ConfigController", remap = false, priority = 900)
public abstract class CraterLibConfigRegistrationMixin {
    @Shadow
    @Final
    private static HashMap<String, Object> watchedConfigs;

    @Unique
    private boolean aeronauticsCompatguard$applied;

    @Unique
    private static final Logger AERONAUTICS_COMPATGUARD_LOGGER = LogUtils.getLogger();

    @Unique
    private static final AtomicBoolean AERONAUTICS_COMPATGUARD_CONFIG_WARNING_LOGGED =
            new AtomicBoolean();

    @Inject(method = "register_config", at = @At("HEAD"), cancellable = true, require = 0)
    private static void aeronauticsCompatGuard$skipSameConfigDuplicate(
            @Coerce Object config,
            CallbackInfo callback
    ) {
        if (!CompatibilityPatchRegistry.isEnabled(
                CompatibilityPatchRegistry.CRATERLIB_CONFIG_IDEMPOTENCY)) {
            return;
        }

        File configPath;
        try {
            Method getConfigPath = config.getClass().getMethod("getConfigPath");
            configPath = (File) getConfigPath.invoke(config);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (AERONAUTICS_COMPATGUARD_CONFIG_WARNING_LOGGED.compareAndSet(false, true)) {
                AERONAUTICS_COMPATGUARD_LOGGER.warn(
                        "[CompatGuard] Could not inspect CraterLib config registration; leaving it unchanged",
                        error);
            }
            return;
        }

        Object registered = watchedConfigs.get(configPath.toString());
        if (registered != null && registered.getClass() == config.getClass()) {
            callback.cancel();
        }
    }
}
