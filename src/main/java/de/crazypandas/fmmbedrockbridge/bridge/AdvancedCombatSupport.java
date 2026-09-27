package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — probes whether EliteMobs' Advanced Combat System is present.
 *
 * <p><b>This class must never import anything from
 * {@code com.magmaguy.elitemobs.advancedcombat}.</b> That package is internal and Alpha; naming
 * it in an import would link it at class-load time and take the whole bridge down with it when
 * MagmaGuy renames something. Everything here goes through reflection on string names.
 *
 * <p>The lesson behind this: on 08.08.2026 a GeyserUtils build compiled against an older Geyser
 * API killed every entity spawn for Bedrock network-wide.
 */
public final class AdvancedCombatSupport {

    private static final String MODULE_CLASS =
            "com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule";
    private static final String SLOT_CLASS =
            "com.magmaguy.elitemobs.advancedcombat.classes.AbilitySlot";

    private static String missingReason = "not probed yet";

    private AdvancedCombatSupport() {
    }

    /** True only when both classes the hook needs can be resolved by name. */
    public static boolean isPresent() {
        for (String className : new String[]{MODULE_CLASS, SLOT_CLASS}) {
            try {
                Class.forName(className, false, AdvancedCombatSupport.class.getClassLoader());
            } catch (Throwable t) {
                missingReason = className + " not resolvable (" + t.getClass().getSimpleName() + ")";
                return false;
            }
        }
        missingReason = "";
        return true;
    }

    /** Why the last {@link #isPresent()} call said no — for the startup log. */
    public static String missingReason() {
        return missingReason;
    }
}
