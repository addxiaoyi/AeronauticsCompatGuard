package dev.aeronautics.compatguard.server;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.items.IItemHandler;

public interface MineColoniesHandlerAccess {
    IItemHandler aeronauticsCompatGuard$getRawHandler(Direction side);
}
