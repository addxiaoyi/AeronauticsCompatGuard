package dev.aeronautics.compatguard.server.mixin;

import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import dev.aeronautics.compatguard.server.CompatibilityServerState;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Pseudo
@Mixin(targets = "net.minecraft.world.item.ItemStack", remap = false)
public abstract class InvalidItemStackParseMixin {
    @Inject(method = "parse", at = @At("HEAD"), cancellable = true, require = 0)
    private static void aeronauticsCompatGuard$rejectAirStack(
            HolderLookup.Provider provider,
            Tag tag,
            CallbackInfoReturnable<Optional<ItemStack>> callback
    ) {
        if (!CompatibilityPatchRegistry.isEnabled(
                CompatibilityPatchRegistry.MINECRAFT_INVALID_ITEM_STACK)
                || !(tag instanceof CompoundTag compound)
                || !isInvalidStack(provider, compound)) {
            return;
        }

        CompatibilityServerState.recordOnActiveServer(
                "item_stack",
                compound.getString("id"),
                "unknown",
                "invalid item stack rejected at ItemStack.parse");
        callback.setReturnValue(Optional.empty());
    }

    private static boolean isInvalidStack(HolderLookup.Provider provider, CompoundTag compound) {
        String id = compound.getString("id");
        if (id.isBlank() || "minecraft:air".equals(id)) {
            return true;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return true;
        }
        try {
            return provider.lookupOrThrow(Registries.ITEM)
                    .get(ResourceKey.create(Registries.ITEM, location))
                    .isEmpty();
        } catch (RuntimeException error) {
            return false;
        }
    }
}
