package dev.aeronautics.compatguard.server;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.Objects;
import java.util.function.Supplier;

public final class StableItemHandler implements IItemHandlerModifiable {
    private final Supplier<IItemHandlerModifiable> currentHandler;

    public StableItemHandler(Supplier<IItemHandlerModifiable> currentHandler) {
        this.currentHandler = Objects.requireNonNull(currentHandler, "currentHandler");
    }

    @Override
    public int getSlots() {
        IItemHandlerModifiable handler = currentHandler.get();
        return handler == null ? 0 : handler.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        IItemHandlerModifiable handler = currentHandler.get();
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return ItemStack.EMPTY;
        }
        return handler.getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        IItemHandlerModifiable handler = currentHandler.get();
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return stack;
        }
        return handler.insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        IItemHandlerModifiable handler = currentHandler.get();
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return ItemStack.EMPTY;
        }
        return handler.extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        IItemHandlerModifiable handler = currentHandler.get();
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return 0;
        }
        return handler.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        IItemHandlerModifiable handler = currentHandler.get();
        return handler != null
                && slot >= 0
                && slot < handler.getSlots()
                && handler.isItemValid(slot, stack);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        IItemHandlerModifiable handler = currentHandler.get();
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return;
        }
        handler.setStackInSlot(slot, stack);
    }
}
