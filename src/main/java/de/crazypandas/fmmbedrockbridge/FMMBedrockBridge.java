package de.crazypandas.fmmbedrockbridge;

import de.crazypandas.fmmbedrockbridge.bridge.BedrockEntityBridge;
import de.crazypandas.fmmbedrockbridge.commands.FMMBridgeCommand;
import de.crazypandas.fmmbedrockbridge.converter.BedrockModelConverter;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import org.bukkit.plugin.java.JavaPlugin;

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
        getServer().getPluginManager().registerEvents(bridge, this);

        boolean packetEventsAvailable = getServer().getPluginManager().getPlugin("packetevents") != null
                || getServer().getPluginManager().getPlugin("PacketEvents") != null;
        if (packetEventsAvailable && geyserUtilsAvailable) {
            bridge.start();
            log.info("PacketEvents: found — fake entity bridge active");
        } else if (!packetEventsAvailable) {
            log.warning("PacketEvents not found — Bedrock entity bridging requires PacketEvents!");
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

    public static FMMBedrockBridge getInstance() {
        return instance;
    }

    public boolean isFloodgateAvailable() {
        return floodgateAvailable;
    }

    public boolean isFmmAvailable() {
        return fmmAvailable;
    }
}
