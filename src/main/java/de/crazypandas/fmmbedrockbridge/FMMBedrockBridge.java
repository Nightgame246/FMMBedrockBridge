package de.crazypandas.fmmbedrockbridge;

import de.crazypandas.fmmbedrockbridge.bridge.BedrockEntityBridge;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import de.crazypandas.fmmbedrockbridge.bridge.EliteMobsItemScanner;
import de.crazypandas.fmmbedrockbridge.commands.FMMBridgeCommand;
import de.crazypandas.fmmbedrockbridge.converter.BedrockModelConverter;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

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

        // Check dependencies
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

        // Save default config
        saveDefaultConfig();

        boolean geyserUtilsAvailable = getServer().getPluginManager().getPlugin("GeyserUtils") != null;
        if (!geyserUtilsAvailable) {
            log.warning("GeyserUtils not found! Bedrock entity bridging will not work.");
        }

        // Start FMM entity tracker with Bedrock bridge
        entityTracker = new FMMEntityTracker(null);
        bridge = new BedrockEntityBridge(floodgateAvailable, geyserUtilsAvailable, entityTracker);
        entityTracker.setBridge(bridge);
        entityTracker.start();
        getServer().getPluginManager().registerEvents(bridge.getViewerManager(), this);

        boolean packetEventsAvailable = getServer().getPluginManager().getPlugin("packetevents") != null
                || getServer().getPluginManager().getPlugin("PacketEvents") != null;
        if (packetEventsAvailable && geyserUtilsAvailable) {
            bridge.start();
            log.info("PacketEvents: found — fake entity bridge active");
        } else if (!packetEventsAvailable) {
            log.warning("PacketEvents not found — Bedrock entity bridging requires PacketEvents!");
        }

        // Phase 7.1c — register combat trigger if EliteMobs is present and the toggle is on
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

        // Phase 7.2b — EliteMobs Custom Items
        if (getConfig().getBoolean("elite-items.enabled", false)) {
            String emPackPath = getConfig().getString("elite-items.resource-pack-path", "plugins/EliteMobs/resource_pack");
            Path emPackRoot = getServer().getWorldContainer().toPath().resolve(emPackPath);
            EliteMobsItemScanner itemScanner = new EliteMobsItemScanner(emPackRoot);
            List<EMCustomItem> emItems = itemScanner.scan();
            if (!emItems.isEmpty()) {
                writeEmItemsJson(emItems);
            }
        }

        // Phase 3: Register converter command
        BedrockModelConverter converter = new BedrockModelConverter();
        FMMBridgeCommand cmd = new FMMBridgeCommand(converter, this);
        getCommand("fmmbridge").setExecutor(cmd);
        getCommand("fmmbridge").setTabCompleter(cmd);

        log.info("GeyserUtils: " + (geyserUtilsAvailable ? "found" : "NOT FOUND"));

        // TODO Phase 3: Resource pack conversion

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

    private void writeEmItemsJson(List<EMCustomItem> items) {
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            File outFile = new File(getDataFolder(), "bedrock-pack/em-items.json");
            outFile.getParentFile().mkdirs();
            File texDir = new File(getDataFolder(), "bedrock-pack/em-item-textures");
            texDir.mkdirs();

            List<EMCustomItem> exportItems = new ArrayList<>();
            for (EMCustomItem item : items) {
                File src = new File(item.sourceTexturePath());
                if (!src.exists()) continue;
                File dst = new File(texDir, item.bedrockTextureKey() + ".png");
                java.nio.file.Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                exportItems.add(new EMCustomItem(
                        item.javaMaterial(),
                        item.customModelData(),
                        "em-item-textures/" + dst.getName(),
                        item.bedrockTextureKey()));
            }

            java.nio.file.Files.writeString(outFile.toPath(), gson.toJson(exportItems));
            log.info("[Phase 7.2b] Wrote " + exportItems.size() + " EM items to " + outFile.getAbsolutePath());
        } catch (Exception e) {
            log.warning("[Phase 7.2b] Failed to write em-items.json: " + e.getMessage());
        }
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

    /**
     * True if the {@code debug} key in config.yml is enabled. Plugin subsystems use this
     * to gate verbose-but-useful diagnostic logging — call {@link #debugLog(String)} for
     * the most common pattern (info-when-debug, fine-otherwise).
     */
    public static boolean isDebugEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("debug", false);
    }

    /**
     * Logs at INFO level if {@code debug=true} in config.yml, otherwise at FINE level.
     * Used for diagnostic messages that are noise in normal operation but valuable when
     * troubleshooting — flip {@code debug: true} to surface them in latest.log without
     * a code change.
     */
    public static void debugLog(String message) {
        if (instance == null) return;
        Logger logger = instance.getLogger();
        if (instance.getConfig().getBoolean("debug", false)) {
            logger.info(message);
        } else {
            logger.fine(message);
        }
    }

    /**
     * Phase 7.1c — true if the combat-triggered visuals (BossBar combat-only, Nametag
     * 3-line during combat) are enabled. When false, Phase 7.1a (BossBar always-visible)
     * + Phase 7.1b (1-line nametag) behavior is preserved as a safety fallback.
     */
    public static boolean isPhase71cCombatEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase71c.combat-enabled", true);
    }
}
