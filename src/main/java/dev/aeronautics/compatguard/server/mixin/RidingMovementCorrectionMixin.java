package dev.aeronautics.compatguard.server.mixin;

import dev.aeronautics.compatguard.AeronauticsCompatGuard;
import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stops the "moved too quickly" rubber-band from firing for a player who is riding something.
 *
 * <p>Evidence from a real server log: {@code ling_you_ moved too quickly! 130.43,10.5,-38.48}.
 * In {@code ServerGamePacketListenerImpl#handleMovePlayer} the branch is
 * <pre>
 * if (distance &gt; threshold &amp;&amp; !isSingleplayerOwner()) {
 *     LOGGER.warn("{} moved too quickly! {},{},{}", ...);
 *     teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
 *     return;
 * }
 * </pre>
 * The server then teleports the player back and returns, discarding the rest of the movement packet.
 * When the player is a passenger the position comes from the vehicle, so correcting the player against
 * a server-side position the vehicle has already left produces a visible snap-back and teleports the
 * player out of moving contraptions. The vehicle's own movement is still validated by
 * {@code handleMoveVehicle}; only this player-position correction is skipped.</p>
 */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class RidingMovementCorrectionMixin {
    @Unique
    private boolean aeronauticsCompatguard$applied;

    @Unique
    private static final AtomicBoolean AERONAUTICS_COMPATGUARD_RIDING_WARNING = new AtomicBoolean();

    @Redirect(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V"),
            require = 0
    )
    private void aeronauticsCompatGuard$skipCorrectionWhileRiding(
            ServerGamePacketListenerImpl listener,
            double x,
            double y,
            double z,
            float yRot,
            float xRot
    ) {
        ServerPlayer player = listener.player;
        if (player != null
                && player.isPassenger()
                && CompatibilityPatchRegistry.isEnabled(
                        CompatibilityPatchRegistry.MINECRAFT_RIDING_MOVEMENT_CORRECTION)) {
            if (AERONAUTICS_COMPATGUARD_RIDING_WARNING.compareAndSet(false, true)) {
                AeronauticsCompatGuard.LOGGER.info(
                        "[CompatGuard] Skipping the moved-too-quickly correction for a riding player; "
                                + "the vehicle owns the position and snapping the player back teleports them out of it");
            }
            return;
        }
        listener.teleport(x, y, z, yRot, xRot);
    }
}
