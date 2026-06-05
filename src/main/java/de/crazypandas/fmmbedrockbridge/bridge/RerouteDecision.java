package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Pure precedence/gating logic for the Bedrock EliteMobs menu reroute.
 *
 * <p>The status menu (Phase 7.3) takes precedence over the quest menu (Phase 7.3b),
 * and each is independently gated by its own config flag. Extracted from
 * {@link BedrockMenuRerouteListener} so the branch selection is unit-testable
 * without Bukkit, Floodgate, or EliteMobs on the classpath.
 */
public final class RerouteDecision {

    public enum Action { NONE, STATUS, QUEST }

    private RerouteDecision() {}

    /**
     * @param statusEnabled config flag {@code phase73.bedrock-dialog-reroute}
     * @param statusMatch   the opened inventory matched a registered status menu title
     * @param questEnabled  config flag {@code phase73.bedrock-quest-reroute}
     * @param questMatch    the opened inventory was recovered as an EM quest menu
     */
    public static Action resolve(boolean statusEnabled, boolean statusMatch,
                                 boolean questEnabled, boolean questMatch) {
        if (statusEnabled && statusMatch) return Action.STATUS;
        if (questEnabled && questMatch) return Action.QUEST;
        return Action.NONE;
    }
}
