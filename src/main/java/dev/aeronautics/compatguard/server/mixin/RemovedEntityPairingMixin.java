package dev.aeronautics.compatguard.server.mixin;

import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips pairing packets for entities that are already marked removed.
 *
 * <p>NeoForge 21.1.250 declares
 * {@code ServerEntity#sendPairingData(ServerPlayer, PacketAndPayloadAcceptor)}. The previous version
 * declared the second parameter as {@code java.util.function.Consumer}, which never matched, so the
 * injector silently never applied ({@code require = 0}) and the guard was dead code on this target.</p>
 */
@Pseudo
@Mixin(targets = "net.minecraft.server.level.ServerEntity", remap = false, priority = 900)
public abstract class RemovedEntityPairingMixin {
    @Unique
    private boolean aeronauticsCompatguard$applied;

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "sendPairingData", at = @At("HEAD"), cancellable = true, require = 0)
    private void aeronauticsCompatGuard$skipRemovedEntityPacket(
            ServerPlayer player,
            PacketAndPayloadAcceptor<?> packetAcceptor,
            CallbackInfo callback
    ) {
        if (entity.isRemoved()
                && CompatibilityPatchRegistry.isEnabled(
                        CompatibilityPatchRegistry.MINECRAFT_REMOVED_ENTITY_TRACKER)) {
            callback.cancel();
        }
    }
}
