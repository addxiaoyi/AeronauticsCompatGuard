package dev.aeronautics.compatguard.server.mixin;

import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import dev.aeronautics.compatguard.server.CompatibilityServerState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes attachment entries whose type (or serializer) is not registered before
 * {@code deserializeAttachments} runs.
 *
 * <p>The previous version only appended a quarantine record at {@code HEAD} and let the original
 * method continue, so the crash it was meant to prevent still happened. Stripping the unknown keys
 * from a copy of the tag makes the load succeed while the raw data stays recoverable in the
 * quarantine manifest.</p>
 */
@Pseudo
@Mixin(value = AttachmentHolder.class, remap = false)
public abstract class UnknownAttachmentDataMixin {
    @ModifyVariable(method = "deserializeAttachments", at = @At("HEAD"), argsOnly = true, require = 0)
    private CompoundTag aeronauticsCompatGuard$stripUnknownAttachments(CompoundTag attachmentData) {
        if (attachmentData == null
                || !CompatibilityPatchRegistry.isEnabled(
                        CompatibilityPatchRegistry.NEOFORGE_UNKNOWN_ATTACHMENT)) {
            return attachmentData;
        }
        List<String> keys = new ArrayList<>(attachmentData.getAllKeys());
        if (keys.isEmpty()) {
            return attachmentData;
        }

        CompoundTag sanitized = null;
        for (String key : keys) {
            ResourceLocation location = ResourceLocation.tryParse(key);
            boolean known = location != null
                    && NeoForgeRegistries.ATTACHMENT_TYPES.containsKey(location)
                    && ((AttachmentTypeSerializerAccessor) (Object)
                            NeoForgeRegistries.ATTACHMENT_TYPES.get(location))
                            .aeronauticsCompatGuard$getSerializer() != null;
            if (known) {
                continue;
            }
            if (sanitized == null) {
                sanitized = attachmentData.copy();
            }
            sanitized.remove(key);
            CompatibilityServerState.recordOnActiveServer(
                    "attachment",
                    key,
                    "unknown",
                    "attachment type is missing or invalid; entry removed before deserialization",
                    attachmentData.get(key).toString());
        }
        return sanitized == null ? attachmentData : sanitized;
    }
}
