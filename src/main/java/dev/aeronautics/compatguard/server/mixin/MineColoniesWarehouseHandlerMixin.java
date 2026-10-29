package dev.aeronautics.compatguard.server.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.aeronautics.compatguard.AeronauticsCompatGuard;
import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import dev.aeronautics.compatguard.server.MineColoniesHandlerAccess;
import dev.aeronautics.compatguard.server.StableItemHandler;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Returns one stable delegate instead of MineColonies' per-tick combined inventory.
 *
 * <p>Upstream behaviour this depends on (checked against
 * {@code ldtteam/minecolonies}, branch {@code version/1.21}):</p>
 *
 * <ul>
 *   <li>{@code TileEntityColonyBuilding#tick()} rebuilds the combined inventory every tick:
 *       {@code if (combinedInv != null) { invalidateCapabilities(); combinedInv = null; }}</li>
 *   <li>{@code TileEntityColonyBuilding#getItemHandlerCap(Direction side)} <em>ignores</em>
 *       {@code side}, lazily builds {@code new CombinedItemHandler(...)} and caches it in one field,
 *       so every side shares a single instance -- a per-{@link Direction} wrapper cache here would
 *       store up to six identical delegates for one handler.</li>
 *   <li>{@code CombinedItemHandler} holds {@code private final IItemHandlerModifiable[] handlers},
 *       so a retained instance keeps the container array it was built with.</li>
 *   <li>Create 6.0.10 {@code CapManipulationBehaviourBase} caches the resolved capability in
 *       {@code targetCapability} and only re-resolves on {@code initialize()},
 *       {@code onNeighborChanged(...)} or every 64 game ticks
 *       ({@code getGameTime() % 64 == 0} in {@code tick()}); it never subscribes to
 *       {@code invalidateCapabilities()}. A cached Create reference therefore points at a
 *       combined inventory MineColonies discarded up to 64 ticks (3.2s) earlier.</li>
 * </ul>
 *
 * <p>This wrapper resolves the current handler on every call, so the object Create cached stays
 * valid; when the block entity dies the wrapper reports zero slots instead of serving the stale
 * snapshot.</p>
 */
@Pseudo
@Mixin(targets = "com.minecolonies.core.tileentities.TileEntityColonyBuilding", remap = false)
public abstract class MineColoniesWarehouseHandlerMixin implements MineColoniesHandlerAccess {
    @Unique
    private static final ThreadLocal<Boolean> AERONAUTICS_COMPATGUARD_RESOLVING =
            ThreadLocal.withInitial(() -> false);

    @Unique
    private static final AtomicBoolean AERONAUTICS_COMPATGUARD_THREAD_WARNING_LOGGED =
            new AtomicBoolean();

    @Unique
    private static final AtomicBoolean AERONAUTICS_COMPATGUARD_ENDPOINT_WARNING =
            new AtomicBoolean();

    @Unique
    private volatile IItemHandler aeronauticsCompatGuard$stableHandler;

    @ModifyReturnValue(method = "getItemHandlerCap", at = @At("RETURN"), require = 0)
    private IItemHandler aeronauticsCompatGuard$refreshWarehouseHandler(
            IItemHandler original,
            Direction side
    ) {
        if (original == null
                || !(original instanceof IItemHandlerModifiable)
                || !original.getClass().getName().equals(
                        "com.minecolonies.api.inventory.api.CombinedItemHandler")
                || AERONAUTICS_COMPATGUARD_RESOLVING.get()
                || !CompatibilityPatchRegistry.isEnabled(
                        CompatibilityPatchRegistry.MINECOLONIES_CREATE_HANDLER)) {
            return original;
        }

        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level == null || level.isClientSide) {
            return original;
        }

        IItemHandler cached = aeronauticsCompatGuard$stableHandler;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (aeronauticsCompatGuard$stableHandler == null) {
                aeronauticsCompatGuard$stableHandler = new StableItemHandler(this::aeronauticsCompatGuard$resolveCurrent);
            }
            return aeronauticsCompatGuard$stableHandler;
        }
    }

    @Unique
    private IItemHandlerModifiable aeronauticsCompatGuard$resolveCurrent() {
        IItemHandler raw = aeronauticsCompatGuard$getRawHandler(null);
        if (raw instanceof IItemHandlerModifiable modifiable) {
            return modifiable;
        }
        if (raw == null && AERONAUTICS_COMPATGUARD_ENDPOINT_WARNING.compareAndSet(false, true)) {
            AeronauticsCompatGuard.LOGGER.info(
                    "[CompatGuard] A retained MineColonies inventory wrapper lost its endpoint "
                            + "(block entity removed, chunk unloaded, or off-thread access); "
                            + "the wrapper now reports zero slots so pipes stop rather than read stale state");
        }
        return null;
    }

    @Override
    public IItemHandler aeronauticsCompatGuard$getRawHandler(Direction side) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        Level level = blockEntity.getLevel();
        if (blockEntity.isRemoved()
                || level == null
                || level.isClientSide
                || !level.hasChunkAt(blockEntity.getBlockPos())) {
            return null;
        }
        if (!(level instanceof ServerLevel serverLevel)
                || !serverLevel.getServer().isSameThread()) {
            if (AERONAUTICS_COMPATGUARD_THREAD_WARNING_LOGGED.compareAndSet(false, true)) {
                AeronauticsCompatGuard.LOGGER.warn(
                        "[CompatGuard] Rejected off-thread access to a MineColonies inventory; "
                                + "the caller must retry on the server thread");
            }
            return null;
        }

        boolean resolving = AERONAUTICS_COMPATGUARD_RESOLVING.get();
        AERONAUTICS_COMPATGUARD_RESOLVING.set(true);
        try {
            return aeronauticsCompatGuard$getItemHandlerCap(side);
        } finally {
            if (resolving) {
                AERONAUTICS_COMPATGUARD_RESOLVING.set(true);
            } else {
                AERONAUTICS_COMPATGUARD_RESOLVING.remove();
            }
        }
    }

    @Shadow(prefix = "aeronauticsCompatGuard$")
    public abstract IItemHandler aeronauticsCompatGuard$getItemHandlerCap(Direction side);
}
