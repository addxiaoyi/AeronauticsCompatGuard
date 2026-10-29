package dev.aeronautics.compatguard.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the adapter version gate.
 *
 * <p>These tests exist because the gate decides whether a compatibility guard is active at all,
 * and a gate that silently says "no" looks exactly like a mod that works. The installed version
 * strings below are copied from the {@code neoforge.mods.toml} of the jars this mod targets, not
 * invented.</p>
 */
class VersionSpecTest {

    /** Real version strings, read from the shipped jars. */
    private static final String MINECOLONIES = "1.1.1405-1.21.1-snapshot";
    private static final String CREATE = "6.0.10";
    private static final String SABLE = "2.0.5";
    private static final String IRIS = "1.8.14-beta.1+mc1.21.1";
    private static final String VEIL = "4.3.2";
    private static final String CRATERLIB = "3.1.3";
    /** The Modrinth release called 4.1.4 ships a jar that still declares 4.1.3+release.0. */
    private static final String SIMPLERPC = "4.1.3+release.0";

    @Test
    void everyGateInTheTargetMatrixMatchesItsInstalledVersion() {
        assertMatched("~1.1.1405", MINECOLONIES);
        assertMatched("~6.0.10", CREATE);
        assertMatched("~2.0.5", SABLE);
        assertMatched("~1.8.14", IRIS);
        assertMatched("~4.3.2", VEIL);
        assertMatched("~3.1.3", CRATERLIB);
        assertMatched("~4.1.3", SIMPLERPC);
    }

    @Test
    void theSimplerpcGateOnlyMatchesOnceItUsesTheDeclaredVersion() {
        // Regression pin: the gate used to be "~4.1.4", which never matched because the jar
        // declares 4.1.3, so the CraterLib config guard was reported as tested but never ran.
        assertFalse(
                VersionSpec.parse("~4.1.4").matches(SIMPLERPC),
                "a gate above the declared version must not match");
        assertTrue(
                VersionSpec.parse("~4.1.3").matches(SIMPLERPC),
                "the gate must match the version the mod actually reports");
    }

    @Test
    void tildeAcceptsLaterPatchesOfTheSameMinorOnly() {
        VersionSpec spec = VersionSpec.parse("~6.0.10");

        assertTrue(spec.matches("6.0.10"));
        assertTrue(spec.matches("6.0.11"));
        assertTrue(spec.matches("6.0.99"));
        assertFalse(spec.matches("6.1.0"), "a different minor is untested");
        assertFalse(spec.matches("6.0.9"), "an older patch is untested");
        assertFalse(spec.matches("5.0.10"), "a different major is untested");
    }

    @Test
    void caretAllowsLaterMinorsWithinTheSameMajor() {
        VersionSpec spec = VersionSpec.parse("^1.2.3");

        assertTrue(spec.matches("1.2.3"));
        assertTrue(spec.matches("1.9.0"));
        assertFalse(spec.matches("2.0.0"));
        assertFalse(spec.matches("1.2.2"));
    }

    @Test
    void comparisonOperatorsFollowTheirNames() {
        assertTrue(VersionSpec.parse(">=1.2.3").matches("1.2.3"));
        assertTrue(VersionSpec.parse(">=1.2.3").matches("1.3.0"));
        assertFalse(VersionSpec.parse(">=1.2.3").matches("1.2.2"));

        assertTrue(VersionSpec.parse(">1.2.3").matches("1.2.4"));
        assertFalse(VersionSpec.parse(">1.2.3").matches("1.2.3"));

        assertTrue(VersionSpec.parse("<=1.2.3").matches("1.2.3"));
        assertFalse(VersionSpec.parse("<=1.2.3").matches("1.2.4"));

        assertTrue(VersionSpec.parse("<1.2.3").matches("1.2.2"));
        assertFalse(VersionSpec.parse("<1.2.3").matches("1.2.3"));
    }

    @Test
    void exactComparisonIgnoresQualifiersButNotNumbers() {
        VersionSpec spec = VersionSpec.parse("1.8.14");

        assertTrue(spec.matches(IRIS), "the -beta qualifier is not part of the comparison");
        assertFalse(spec.matches("1.8.15"));
        assertTrue(
                VersionSpec.parse("6.0.10").matches("6.0.10.0"),
                "trailing zero segments compare equal");
    }

    @Test
    void wildcardsStopAtTheWildcardSegment() {
        assertTrue(VersionSpec.parse("6.0.x").matches("6.0.10"));
        assertTrue(VersionSpec.parse("6.0.x").matches("6.0.0"));
        assertFalse(VersionSpec.parse("6.0.x").matches("6.1.0"));
        assertTrue(VersionSpec.parse("6.x").matches("6.9.9"));
    }

    @Test
    void starAndEmptySpecsMatchAnyVersion() {
        assertTrue(VersionSpec.parse("*").matches(CREATE));
        assertTrue(VersionSpec.parse("  ").matches(CREATE));
    }

    @Test
    void unparseableVersionsAreRefusedRatherThanAssumedCompatible() {
        // Conservative on purpose: a guard must not enable itself on a version it cannot compare.
        assertFalse(VersionSpec.parse("~6.0.10").matches("v6.0.10"));
        assertFalse(VersionSpec.parse("~6.0.10").matches("snapshot"));
        assertFalse(VersionSpec.parse("~6.0.10").matches(""));
        assertFalse(VersionSpec.parse("~6.0.10").matches(null));
    }

    @Test
    void versionParsingKeepsOnlyTheLeadingNumericRelease() {
        assertArrayEquals(new int[] {1, 1, 1405}, VersionSpec.numbers(MINECOLONIES));
        assertArrayEquals(new int[] {1, 8, 14}, VersionSpec.numbers(IRIS));
        assertArrayEquals(new int[] {4, 1, 3}, VersionSpec.numbers(SIMPLERPC));
        assertArrayEquals(new int[] {6, 0, 10}, VersionSpec.numbers(CREATE));
    }

    private static void assertMatched(String gate, String installed) {
        assertTrue(
                VersionSpec.parse(gate).matches(installed),
                "gate " + gate + " must accept the installed version " + installed);
    }
}
