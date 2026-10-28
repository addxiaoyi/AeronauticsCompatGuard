package dev.aeronautics.compatguard.server.mixin;

import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import dev.aeronautics.compatguard.server.CompatibilityServerState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = AttachmentHolder.class, remap = false)
public abstract class UnknownAttachmentDataMixin {
    @Inject(method = "deserializeAttachments", at = @At("HEAD"), require = 0)
    private void aeronauticsCompatGuard$quarantineUnknownAttachments(
            HolderLookup.Provider provider,
            CompoundTag attachmentData,
            CallbackInfo callback
    ) {
        if (!CompatibilityPatchRegistry.isEnabled(
                CompatibilityPatchRegistry.NEOFORGE_UNKNOWN_ATTACHMENT)) {
            return;
        }

        for (String key : attachmentData.getAllKeys()) {
            ResourceLocation location = ResourceLocation.tryParse(key);
            if (location != null && NeoForgeRegistries.ATTACHMENT_TYPES.containsKey(location)
                    && ((AttachmentTypeSerializerAccessor) (Object)
                            NeoForgeRegistries.ATTACHMENT_TYPES.get(location))
                            .aeronauticsCompatGuard$getSerializer() != null) {
                continue;
            }
            CompatibilityServerState.recordOnActiveServer(
                    "attachment",
                    key,
                    "unknown",
                    "attachment type is missing or invalid",
                    attachmentData.get(key).toString());
        }
    }
}
