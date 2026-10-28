package dev.aeronautics.compatguard.server.mixin;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(value = AttachmentType.class, remap = false)
public interface AttachmentTypeSerializerAccessor {
    @Accessor("serializer")
    IAttachmentSerializer<?, ?> aeronauticsCompatGuard$getSerializer();
}
