package de.crazypandas.fmmbedrockbridge;

import de.crazypandas.fmmbedrockbridge.bridge.AdvancedCombatSupport;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockEntityBridge;
import de.crazypandas.fmmbedrockbridge.commands.FMMBridgeCommand;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * FMMBedrockBridge — EM↔Bedrock UX-Bridge.
 *
 * Requires:
 *  - FreeMinecraftModels 2.6.0+ with sendCustomModelsToBedrockClients: true (mob/static rendering)
 *  - ResourcePackManager 2.0.2+ (Java→Bedrock pack + EM UI item conversion)
 *  - PacketEvents (packet manipulation)
 *  - Floodgate (Bedrock player detection)
 *
 * What this plugin does (and what MagmaGuy doesn't cover):
 *  - Phase 7.1a/c: Combat-triggered styled BossBar with HP sync, suppresses EM's vanilla "Evoker | 2"
 *  - Phase 7.1b/c: Combat-triggered Bedrock nametag overlay (HP / HP-Bar; FMM renders name)
 */
public class FMMBedrockBridge extends JavaPlugin {

    private static FMMBedrockBridge instance;
    private static Logger log;

    private boolean floodgateAvailable = false;
    private boolean fmmAvailable = false;

    private FMMEntityTracker entityTracker;
    private BedrockEntityBridge bridge;

    @Override
    public void onEnable() {
        instance = this;
        log = getLogger();

        fmmAvailable = getServer().getPluginManager().getPlugin("FreeMinecraftModels") != null;
        floodgateAvailable = getServer().getPluginManager().getPlugin("floodgate") != null;

        if (!fmmAvailable) {
            log.severe("FreeMinecraftModels not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!floodgateAvailable) {
            log.warning("Floodgate not found! Bedrock player detection will not work.");
        }

        saveDefaultConfig();

        entityTracker = new FMMEntityTracker(null);
        bridge = new BedrockEntityBridge(floodgateAvailable, entityTracker);
        entityTracker.setBridge(bridge);
        entityTracker.start();
        getServer().getPluginManager().registerEvents(bridge.getViewerManager(), this);

        boolean packetEventsAvailable = getServer().getPluginManager().getPlugin("packetevents") != null
                || getServer().getPluginManager().getPlugin("PacketEvents") != null;
        if (packetEventsAvailable) {
            bridge.start();
            log.info("PacketEvents: found — packet interception active");
        } else {
            log.warning("PacketEvents not found — Phase 7.1a BossBar suppression disabled");
        }

        // Phase 7.1c — register combat trigger if EliteMobs is present
        boolean elitemobsAvailable = getServer().getPluginManager().getPlugin("EliteMobs") != null;
        if (elitemobsAvailable && isPhase71cCombatEnabled()) {
            try {
                getServer().getPluginManager().registerEvents(
                        new de.crazypandas.fmmbedrockbridge.bridge.BedrockCombatTrigger(bridge), this);
                log.info("Phase 7.1c: combat trigger registered (BossBar + nametag combat-only)");
            } catch (Throwable t) {
                log.warning("Phase 7.1c: failed to register combat trigger — falling back to always-visible BossBar. Cause: " + t);
            }
        } else {
            log.info("Phase 7.1c: combat trigger NOT registered (EliteMobs="
                    + elitemobsAvailable + ", combat-enabled=" + isPhase71cCombatEnabled() + ")");
        }

        // Phase 7.3 / 7.3b — reroute Bedrock EM chest menus to EM's native MC dialogs so
        // Geyser renders them as native Bedrock forms. Requires MC >= 1.21.6.
        // getBukkitVersion() ("1.21.10-R0.1-SNAPSHOT") is always present; McVersions
        // strips the "-R0.1-SNAPSHOT" suffix before comparing.
        boolean mc1216 = de.crazypandas.fmmbedrockbridge.bridge.McVersions
                .isAtLeast(getServer().getBukkitVersion(), 1, 21, 6);
        boolean statusReroute = getConfig().getBoolean("phase73.bedrock-dialog-reroute", true);
        boolean questReroute = getConfig().getBoolean("phase73.bedrock-quest-reroute", true);
        boolean anyReroute = statusReroute || questReroute;
        if (floodgateAvailable && elitemobsAvailable && mc1216 && anyReroute) {
            getServer().getPluginManager().registerEvents(
                    new de.crazypandas.fmmbedrockbridge.bridge.BedrockMenuRerouteListener(this), this);
            log.info("Phase 7.3: Bedrock menu dialog-reroute registered (status=" + statusReroute
                    + ", quest=" + questReroute + ")");
        } else {
            log.info("Phase 7.3: reroute NOT registered (floodgate=" + floodgateAvailable
                    + ", em=" + elitemobsAvailable + ", mc>=1.21.6=" + mc1216
                    + ", status=" + statusReroute + ", quest=" + questReroute + ")");
        }

        // Phase 7.4 — sneak-based ability input for Bedrock players.
        // EliteMobs' F-chord cannot be produced by Bedrock clients; see the upstream report.
        if (floodgateAvailable && elitemobsAvailable && isPhase74Enabled()) {
            if (AdvancedCombatSupport.isPresent()) {
                try {
                    de.crazypandas.fmmbedrockbridge.bridge.AdvancedCombatHook hook =
                            new de.crazypandas.fmmbedrockbridge.bridge.AdvancedCombatHook();
                    // Phase 7.5 needs the hook too: it replaces EliteMobs' glyph HUD for Bedrock
                    // players with a line built from the same values.
                    if (bridge != null) bridge.getPacketInterceptor().setAdvancedCombatHook(hook);
                    // The listener reads its config per event, like every other phase, instead
                    // of freezing it here — so a switch flipped during the playtest only needs
                    // the config reloaded, not the whole server restarted.
                    getServer().getPluginManager().registerEvents(
                            new de.crazypandas.fmmbedrockbridge.bridge.BedrockAbilityListener(hook),
                            this);
                    log.info("Phase 7.4: Bedrock ability input registered (sneak-held controls,"
                            + " require-combat=" + isPhase74RequireCombat() + ")");
                } catch (Throwable t) {
                    // Alpha package: this feature may break, the plugin must not.
                    log.warning("Phase 7.4: registration failed, Bedrock ability input disabled. Cause: " + t);
                }
            } else {
                log.info("Phase 7.4: EliteMobs Advanced Combat System not available ("
                        + AdvancedCombatSupport.missingReason() + ") — Bedrock ability input off");
            }
        } else {
            log.info("Phase 7.4: NOT registered (floodgate=" + floodgateAvailable
                    + ", em=" + elitemobsAvailable + ", enabled=" + isPhase74Enabled() + ")");
        }

        FMMBridgeCommand cmd = new FMMBridgeCommand(this);
        getCommand("fmmbridge").setExecutor(cmd);
        getCommand("fmmbridge").setTabCompleter(cmd);

        log.info("FMMBedrockBridge v" + getDescription().getVersion() + " enabled!");
        log.info("FreeMinecraftModels: " + (fmmAvailable ? "found" : "NOT FOUND"));
        log.info("Floodgate: " + (floodgateAvailable ? "found" : "NOT FOUND"));
    }

    @Override
    public void onDisable() {
        if (bridge != null) bridge.shutdown();
        if (entityTracker != null) entityTracker.shutdown();
        log.info("FMMBedrockBridge disabled.");
    }

    public static FMMBedrockBridge getInstance() {
        return instance;
    }

    public BedrockEntityBridge getBridge() {
        return bridge;
    }

    public boolean isFloodgateAvailable() {
        return floodgateAvailable;
    }

    public boolean isFmmAvailable() {
        return fmmAvailable;
    }

    public static boolean isDebugEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("debug", false);
    }

    public static void debugLog(String message) {
        if (instance == null) return;
        Logger logger = instance.getLogger();
        if (instance.getConfig().getBoolean("debug", false)) {
            logger.info(message);
        } else {
            logger.fine(message);
        }
    }

    public static boolean isPhase71cCombatEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase71c.combat-enabled", true);
    }

    public static boolean isPhase71cDamageRefreshEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase71c.damage-refresh-enabled", true);
    }

    public static boolean isPhase71cHideOnExitEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase71c.hide-on-exit-event", false);
    }

    public static long getPhase71cDamageTimeoutTicks() {
        FMMBedrockBridge plugin = instance;
        return plugin != null ? plugin.getConfig().getLong("phase71c.damage-timeout-ticks", 0L) : 0L;
    }

    public static boolean isPhase74Enabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase74.bedrock-abilities", true);
    }


    public static boolean isPhase74RequireCombat() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase74.require-combat", true);
    }

    public static boolean isPhase75Enabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase75.strip-java-font-glyphs", true);
    }

    public static boolean isPhase74FeedbackEnabled() {
        FMMBedrockBridge plugin = instance;
        // Default OFF: EliteMobs reports ability failures itself through its ActionBarCompositor
        // (with a keepalive re-render loop), so a second writer in the same tick flickers.
        return plugin != null && plugin.getConfig().getBoolean("phase74.feedback", false);
    }

}
