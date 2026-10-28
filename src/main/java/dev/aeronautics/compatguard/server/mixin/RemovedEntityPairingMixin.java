package dev.aeronautics.compatguard.server.mixin;

import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "net.minecraft.server.level.ServerEntity", remap = false)
public abstract class RemovedEntityPairingMixin {
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "sendPairingData", at = @At("HEAD"), cancellable = true, require = 0)
    private void aeronauticsCompatGuard$skipRemovedEntityPacket(
            ServerPlayer player,
            Consumer<?> packetConsumer,
            CallbackInfo callback
    ) {
        if (entity.isRemoved()
                && CompatibilityPatchRegistry.isEnabled(
                        CompatibilityPatchRegistry.MINECRAFT_REMOVED_ENTITY_TRACKER)) {
            callback.cancel();
        }
    }
}
