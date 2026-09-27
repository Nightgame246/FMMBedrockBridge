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

    /**
     * Phase 7.3c — whether EliteMobs' class menu should render as its dialog instead of the chest.
     *
     * <p>EliteMobs' own rule ({@code MenuPresentation.supportsDialogs}) is
     * {@code useBookMenus && !isBedrock && !onlyUseBedrockMenus && mc >= 1.21.6}. This answers
     * yes exactly when Bedrock is the ONLY reason for the chest: a player who picked chest menus,
     * or a server set to chests only, keeps them.
     *
     * @param enabled          config flag {@code phase73.bedrock-class-reroute}
     * @param bedrock          the player is on Bedrock (same detection as EliteMobs)
     * @param useBookMenus     EliteMobs' per-player menu style ({@code PlayerData.getUseBookMenus})
     * @param onlyBedrockMenus EliteMobs' server-wide {@code DefaultConfig.isOnlyUseBedrockMenus}
     * @param mc1216           server is on MC >= 1.21.6, the first version with dialogs
     */
    public static boolean classMenuUsesDialog(boolean enabled, boolean bedrock, boolean useBookMenus,
                                              boolean onlyBedrockMenus, boolean mc1216) {
        return enabled && bedrock && useBookMenus && !onlyBedrockMenus && mc1216;
    }
}
