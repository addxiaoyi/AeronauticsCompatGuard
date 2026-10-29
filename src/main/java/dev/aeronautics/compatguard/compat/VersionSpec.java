package dev.aeronautics.compatguard.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tolerant version gate used by {@link CompatibilityPatchRegistry}.
 *
 * <p>The previous gate compared the installed version string against an exact set, so a patch
 * release such as {@code create 6.0.11} silently disabled a guard that was still correct. This class
 * keeps the "only enable what was tested" rule while allowing explicitly declared tolerance:</p>
 *
 * <ul>
 *   <li>{@code *} — any installed version;</li>
 *   <li>{@code 1.2.3} — exactly this version (numeric comparison, qualifiers ignored);</li>
 *   <li>{@code ~1.2.3} — same major.minor, patch &gt;= 3;</li>
 *   <li>{@code ^1.2.3} — same major, version &gt;= 1.2.3;</li>
 *   <li>{@code >=1.2.3}, {@code >1.2.3}, {@code <=1.2.3}, {@code <1.2.3} — bounds;</li>
 *   <li>{@code 1.2.x} — wildcard on any trailing segment.</li>
 * </ul>
 *
 * <p>Installed versions are reduced to their leading numeric triple, so
 * {@code 1.8.14-beta.1+mc1.21.1} parses as {@code 1.8.14} and
 * {@code 1.1.1405-1.21.1-snapshot} parses as {@code 1.1.1405}.</p>
 */
public final class VersionSpec {
    private final String raw;
    private final Kind kind;
    private final int[] target;

    private VersionSpec(String raw, Kind kind, int[] target) {
        this.raw = raw;
        this.kind = kind;
        this.target = target;
    }

    public enum Kind { ANY, EXACT, TILDE, CARET, GE, GT, LE, LT, WILDCARD }

    public static VersionSpec parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        String trimmed = spec.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return new VersionSpec(trimmed, Kind.ANY, new int[0]);
        }
        if (trimmed.startsWith(">=")) {
            return new VersionSpec(trimmed, Kind.GE, numbers(trimmed.substring(2)));
        }
        if (trimmed.startsWith("<=")) {
            return new VersionSpec(trimmed, Kind.LE, numbers(trimmed.substring(2)));
        }
        if (trimmed.startsWith(">")) {
            return new VersionSpec(trimmed, Kind.GT, numbers(trimmed.substring(1)));
        }
        if (trimmed.startsWith("<")) {
            return new VersionSpec(trimmed, Kind.LT, numbers(trimmed.substring(1)));
        }
        if (trimmed.startsWith("~")) {
            return new VersionSpec(trimmed, Kind.TILDE, numbers(trimmed.substring(1)));
        }
        if (trimmed.startsWith("^")) {
            return new VersionSpec(trimmed, Kind.CARET, numbers(trimmed.substring(1)));
        }
        if (trimmed.contains("x") || trimmed.contains("*")) {
            return new VersionSpec(trimmed, Kind.WILDCARD, numbers(trimmed));
        }
        return new VersionSpec(trimmed, Kind.EXACT, numbers(trimmed));
    }

    public boolean matches(String installedVersion) {
        if (installedVersion == null || installedVersion.isBlank()) {
            return false;
        }
        if (kind == Kind.ANY) {
            return true;
        }
        int[] installed = numbers(installedVersion);
        if (installed.length == 0 || target.length == 0) {
            return false;
        }
        return switch (kind) {
            case EXACT -> compare(installed, target) == 0;
            case TILDE -> compare(installed, target) >= 0
                    && installed[0] == target[0]
                    && installed.length > 1 && target.length > 1 && installed[1] == target[1];
            case CARET -> compare(installed, target) >= 0 && installed[0] == target[0];
            case GE -> compare(installed, target) >= 0;
            case GT -> compare(installed, target) > 0;
            case LE -> compare(installed, target) <= 0;
            case LT -> compare(installed, target) < 0;
            case WILDCARD -> matchesWildcard(installed, raw);
            case ANY -> true;
        };
    }

    private static boolean matchesWildcard(int[] installed, String spec) {
        String[] parts = spec.split("\\.");
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if ("x".equals(part) || "*".equals(part)) {
                return true;
            }
            int parsed;
            try {
                parsed = Integer.parseInt(part.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException error) {
                return false;
            }
            if (index >= installed.length || installed[index] != parsed) {
                return false;
            }
        }
        return true;
    }

    private static int compare(int[] left, int[] right) {
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int a = index < left.length ? left[index] : 0;
            int b = index < right.length ? right[index] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    /** Extracts the leading numeric release triple; qualifiers such as {@code -beta.1+mc1.21.1} are ignored. */
    static int[] numbers(String version) {
        List<Integer> values = new ArrayList<>(4);
        int index = 0;
        while (index < version.length() && values.size() < 4) {
            while (index < version.length() && !Character.isDigit(version.charAt(index))) {
                if (values.isEmpty() && version.charAt(index) == '-') {
                    index++;
                    continue;
                }
                break;
            }
            int start = index;
            while (index < version.length() && Character.isDigit(version.charAt(index))) {
                index++;
            }
            if (start == index) {
                break;
            }
            try {
                values.add(Integer.parseInt(version.substring(start, index)));
            } catch (NumberFormatException error) {
                break;
            }
            if (index < version.length() && version.charAt(index) == '.') {
                index++;
                continue;
            }
            break;
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public String toString() {
        return raw;
    }
}
