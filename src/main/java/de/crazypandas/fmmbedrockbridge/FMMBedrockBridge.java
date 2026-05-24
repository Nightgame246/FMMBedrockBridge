package de.crazypandas.fmmbedrockbridge;

import de.crazypandas.fmmbedrockbridge.bridge.BedrockEntityBridge;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import de.crazypandas.fmmbedrockbridge.bridge.EliteMobsItemScanner;
import de.crazypandas.fmmbedrockbridge.commands.FMMBridgeCommand;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * FMMBedrockBridge — EM↔Bedrock UX-Bridge.
 *
 * Requires:
 *  - FreeMinecraftModels 2.6.0+ with sendCustomModelsToBedrockClients: true (mob/static rendering)
 *  - ResourcePackManager 1.8.0+ (Java→Bedrock pack conversion)
 *  - PacketEvents (packet manipulation)
 *  - Floodgate (Bedrock player detection)
 *
 * What this plugin does (and what MagmaGuy doesn't cover):
 *  - Phase 7.1a/c: Combat-triggered styled BossBar with HP sync, suppresses EM's vanilla "Evoker | 2"
 *  - Phase 7.1b/c: Combat-triggered 3-line nametag (HP / HP-Bar / Name)
 *  - Phase 7.2b: 2D EM UI icons (BagOfCoin, AnvilHammer, …) via item_model packet injection — fills
 *    RPM's gap for legacy custom_model_data overrides on Emerald
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
            log.warning("PacketEvents not found — Phase 7.1a BossBar suppression + Phase 7.2b 2D-item inject disabled");
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

        // Phase 7.2b — EliteMobs 2D Custom Items (Map+Inject)
        java.util.Map<String, java.util.Map<Integer, String>> emItemModelMap = new java.util.HashMap<>();
        if (getConfig().getBoolean("elite-items.enabled", false)) {
            String emPackPath = getConfig().getString("elite-items.resource-pack-path", "plugins/EliteMobs/resource_pack");
            Path emPackRoot = getServer().getWorldContainer().toPath().resolve(emPackPath);
            EliteMobsItemScanner itemScanner = new EliteMobsItemScanner(emPackRoot);
            List<EMCustomItem> emItems = itemScanner.scan();
            if (!emItems.isEmpty()) {
                writeEmItemsJson(emItems);
                for (EMCustomItem item : emItems) {
                    emItemModelMap.computeIfAbsent(item.javaMaterial(), k -> new java.util.HashMap<>())
                            .put(item.customModelData(), item.bedrockTextureKey());
                }
            }
        }

        if (packetEventsAvailable && !emItemModelMap.isEmpty()) {
            bridge.getPacketInterceptor().setEmItemModelMap(emItemModelMap);
        }

        // Phase 7.2b — re-send Bedrock inventories after client-side item moves so the
        // PacketInterceptor can re-inject item_model (no server packet fires otherwise).
        if (floodgateAvailable && !emItemModelMap.isEmpty()) {
            getServer().getPluginManager().registerEvents(
                    new de.crazypandas.fmmbedrockbridge.bridge.BedrockInventoryRefresher(this), this);
            log.info("Phase 7.2b: BedrockInventoryRefresher registered");
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
}
