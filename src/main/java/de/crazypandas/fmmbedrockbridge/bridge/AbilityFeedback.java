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
