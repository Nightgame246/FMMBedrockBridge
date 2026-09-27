package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — action-bar text for Bedrock players.
 *
 * <p>Plain text on purpose: EliteMobs' graphical combat HUD renders through Java resource-pack
 * font providers, which Bedrock cannot resolve.
 *
 * <p>Takes the failure reason as a <b>string</b> rather than the enum, so this class stays on
 * the safe side of the firewall described in {@link AdvancedCombatSupport}.
 */
public final class AbilityFeedback {

    private AbilityFeedback() {
    }

    /** Confirmation line for a fired ability. */
    public static String forSuccess(String abilityName) {
        if (abilityName == null || abilityName.isBlank()) return "§a▶";
        return "§a▶ " + abilityName;
    }

    /**
     * Controls line shown when a Bedrock player crouches — the counterpart of the line EliteMobs
     * shows Java players when F opens its chord ({@code ClassAbilityInputRouter.openGesture}),
     * with the same hotbar numbers and our gestures instead of F/LMB/RMB.
     *
     * <p>Like EliteMobs, advertises the mirror key (7/8/9) for the slot already held: pressing
     * that slot's own digit sends no slot change, so it could not work.
     *
     * @param heldSlot zero-based hotbar slot currently held
     */
    public static String controlsHint(int heldSlot, String mobility, String signature, String utility) {
        return "§7[" + (heldSlot == 0 ? "7" : "1") + "/2×Ducken] §f" + orElse(mobility, "Mobility")
                + "  §7[" + (heldSlot == 1 ? "8" : "2") + "/Links] §f" + orElse(signature, "Signature")
                + "  §7[" + (heldSlot == 2 ? "9" : "3") + "/Rechts] §f" + orElse(utility, "Utility");
    }

    private static String orElse(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    /**
     * Text for a failed attempt, or {@code null} when the player should see nothing —
     * technical and unknown reasons stay silent rather than leaking enum names.
     */
    public static String forFailure(String failureReasonName) {
        if (failureReasonName == null) return null;
        return switch (failureReasonName) {
            case "NO_VALID_TARGET" -> "§7Kein Ziel";
            case "PATH_BLOCKED", "UNSAFE_DESTINATION" -> "§7Weg blockiert";
            case "INVALID_LEVEL" -> "§7Fähigkeit noch nicht freigeschaltet";
            default -> null;
        };
    }
}
