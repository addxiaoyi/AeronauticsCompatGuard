package dev.aeronautics.compatguard.compat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Pure matching and ordering logic for cross-mod mixin conflicts.
 *
 * <p>Kept free of Minecraft types so it can be exercised without a game instance; the inventory in
 * {@link MixinInventory} feeds it real data and this class decides what overlaps and who runs first.</p>
 */
public final class ConflictMatcher {
    public record Foreign(String modId, String mixinClass, List<String> targetClasses,
                          int priority, List<String> injections) {}

    public record Target(String id, String className, String method) {}

    private ConflictMatcher() {}

    public static List<Foreign> overlapping(Collection<Foreign> foreign, Target target) {
        List<Foreign> hits = new ArrayList<>();
        for (Foreign candidate : foreign) {
            boolean sameClass = candidate.targetClasses().stream()
                    .anyMatch(name -> name.equals(target.className()));
            if (!sameClass) {
                continue;
            }
            boolean sameMethod = candidate.injections().isEmpty()
                    || candidate.injections().stream().anyMatch(injection -> injection.contains(target.method()));
            if (sameMethod) {
                hits.add(candidate);
            }
        }
        return hits;
    }

    /** Describes ordering relative to this mod's fixed priority of 900. */
    public static String ordering(int foreignPriority) {
        if (foreignPriority < 900) {
            return "runs BEFORE us (priority " + foreignPriority + ")";
        }
        if (foreignPriority == 900) {
            return "same priority 900 (order undefined)";
        }
        return "runs AFTER us (priority " + foreignPriority + ")";
    }

    public static List<String> report(Collection<Foreign> foreign, List<Target> targets) {
        List<String> lines = new ArrayList<>();
        for (Target target : targets) {
            for (Foreign other : overlapping(foreign, target)) {
                String simpleName = other.mixinClass().substring(other.mixinClass().lastIndexOf('.') + 1);
                lines.add("CONFLICT " + target.id() + " <- " + other.modId() + ":" + simpleName
                        + " " + ordering(other.priority()) + " " + other.injections());
            }
        }
        if (lines.isEmpty()) {
            lines.add("no overlap with our " + targets.size() + " declared targets");
        }
        return lines;
    }
}
