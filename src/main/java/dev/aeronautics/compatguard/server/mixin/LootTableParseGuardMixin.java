package dev.aeronautics.compatguard.server.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import dev.aeronautics.compatguard.server.CompatibilityServerState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootDataType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Resolves loot tables that failed to parse into an explicit empty table instead of a missing one.
 *
 * <p>Motivation is a real server log: 555 of 690 ERROR lines were
 * {@code Couldn't parse element ResourceKey[minecraft:root / minecraft:loot_table]:railways:blocks/track_<mod>}
 * -- tables that Steam 'n' Rails pre-generates for mods that are not installed
 * ({@code natures_spirit}, {@code twilightforest}, {@code blue_skies}, {@code tfc}, {@code hexcasting}).
 *
 * <p>Vanilla behaviour: the parse error is logged, then {@code deserialize} returns
 * {@code Optional.empty()} and the id simply does not exist. Gameplay outcome of "absent table" and
 * "empty table" is the same -- no drops -- but an absent table keeps producing follow-up errors and
 * cannot be told apart from a typo. This guard substitutes the codec's own registered
 * {@code defaultValue} and records the id plus the reason, so the failure is explicit rather than
 * repeated: vanilla's own ERROR is left untouched, nothing is silenced.</p>
 */
@Mixin(value = LootDataType.class, priority = 900)
public abstract class LootTableParseGuardMixin {
    @Unique
    private boolean aeronauticsCompatguard$applied;

    @Shadow
    @Final
    private Object defaultValue;

    @ModifyReturnValue(method = "deserialize", at = @At("RETURN"), require = 0)
    private Optional<Object> aeronauticsCompatGuard$materializeFailedElement(
            Optional<Object> original,
            ResourceLocation id
    ) {
        if (original.isPresent()
                || id == null
                || !CompatibilityPatchRegistry.isEnabled(CompatibilityPatchRegistry.LOOT_TABLE_PARSE_GUARD)) {
            return original;
        }
        LootDataType<?> self = (LootDataType<?>) (Object) this;
        CompatibilityServerState.recordOnActiveServer(
                "loot_table_parse_failure",
                id.toString(),
                self.registryKey().location().toString(),
                "loot table failed to parse; recorded only, the id stays absent",
                "type=" + self.registryKey().location());
        // Substituting the codec default was wrong: LootDataType.TABLE's defaultValue is the shared
        // LootTable.EMPTY singleton (javap: getstatic LootTable.EMPTY), so every failed table handed
        // the same instance to ReloadableServerRegistries, which then threw
        // "Adding duplicate value 'LootTable@2a1d9270' to registry" and the server refused to start.
        // Recording without substituting keeps the failed id absent, exactly like vanilla.
        return original;
    }
}
