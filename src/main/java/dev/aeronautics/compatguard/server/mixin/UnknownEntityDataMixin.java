package dev.aeronautics.compatguard.server.mixin;

import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import dev.aeronautics.compatguard.server.CompatibilityServerState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

@Pseudo
@Mixin(value = EntityType.class, remap = false, priority = 900)
public abstract class UnknownEntityDataMixin {
    @Unique
    private boolean aeronauticsCompatguard$applied;

    @Inject(method = "loadEntityRecursive", at = @At("HEAD"), cancellable = true, require = 0)
    private static void aeronauticsCompatGuard$quarantineUnknownEntity(
            CompoundTag tag,
            Level level,
            Function<Entity, Entity> processor,
            CallbackInfoReturnable<Entity> callback
    ) {
        if (!(level instanceof ServerLevel serverLevel)
                || !CompatibilityPatchRegistry.isEnabled(
                        CompatibilityPatchRegistry.MINECRAFT_UNKNOWN_ENTITY_DATA)) {
            return;
        }

        String id = tag.getString("id");
        ResourceLocation location = ResourceLocation.tryParse(id);
        // Namespace is not a proxy for "still exists": a vanilla id can be removed between versions
        // (or be a datapack-removed entry), and those were previously rethrown straight into the load.
        if (location == null
                || net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(location)) {
            return;
        }

        CompatibilityServerState.recordOnActiveServer(
                "entity",
                id,
                serverLevel.dimension().location().toString(),
                "unknown entity type in saved world data",
                tag.toString());
        callback.setReturnValue(null);
    }
}
