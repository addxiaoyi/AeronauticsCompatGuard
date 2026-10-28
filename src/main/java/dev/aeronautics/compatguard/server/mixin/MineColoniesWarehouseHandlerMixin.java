package dev.aeronautics.compatguard.server.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.aeronautics.compatguard.server.MineColoniesHandlerAccess;
import dev.aeronautics.compatguard.server.StableItemHandler;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "com.minecolonies.core.tileentities.TileEntityColonyBuilding", remap = false)
public abstract class MineColoniesWarehouseHandlerMixin implements MineColoniesHandlerAccess {
    @Unique
    private static final ThreadLocal<Boolean> AERONAUTICS_COMPATGUARD_RESOLVING =
            ThreadLocal.withInitial(() -> false);

    @Unique
    private final Map<Direction, IItemHandler> aeronauticsCompatGuard$stableHandlers =
            new HashMap<>();

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
                || !ModList.get().isLoaded("minecolonies")
                || !ModList.get().isLoaded("create")) {
            return original;
        }

        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level == null || level.isClientSide) {
            return original;
        }

        return aeronauticsCompatGuard$stableHandlers.computeIfAbsent(
                side,
                ignored -> new StableItemHandler(
                        () -> aeronauticsCompatGuard$getRawHandler(side)
                                instanceof IItemHandlerModifiable modifiable ? modifiable : null));
    }

    @Override
    public IItemHandler aeronauticsCompatGuard$getRawHandler(Direction side) {
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
