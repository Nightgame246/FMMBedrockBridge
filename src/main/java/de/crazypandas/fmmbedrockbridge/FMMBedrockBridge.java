package de.crazypandas.fmmbedrockbridge;

import de.crazypandas.fmmbedrockbridge.bedrock.BedrockItemPackBuilder;
import de.crazypandas.fmmbedrockbridge.bedrock.GeyserMappingsWriter;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockEntityBridge;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import de.crazypandas.fmmbedrockbridge.bridge.EliteMobsItemScanner;
import de.crazypandas.fmmbedrockbridge.commands.FMMBridgeCommand;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceState;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceStateStore;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceTracker;
import de.crazypandas.fmmbedrockbridge.maintenance.OpDriftNotifier;
import de.crazypandas.fmmbedrockbridge.maintenance.PackHashCalculator;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *  - Phase 7.1b/c: Combat-triggered Bedrock nametag overlay (HP / HP-Bar; FMM renders name)
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

    private final AtomicBoolean driftActive = new AtomicBoolean(false);
    private String currentPackHash = "";
    private String currentEmVersion = "";
    private MaintenanceTracker maintenanceTracker;
    private java.nio.file.Path mcpackPath;
    private java.nio.file.Path mappingsJsonPath;

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
        java.util.List<EMCustomItem> emItems = java.util.Collections.emptyList();
        if (getConfig().getBoolean("elite-items.enabled", false)) {
            String emPackPath = getConfig().getString("elite-items.resource-pack-path", "plugins/EliteMobs/resource_pack");
            Path emPackRoot = getServer().getWorldContainer().toPath().resolve(emPackPath);
            EliteMobsItemScanner itemScanner = new EliteMobsItemScanner(emPackRoot);
            emItems = itemScanner.scan();
            if (!emItems.isEmpty()) {
                writeEmItemsJson(emItems);
                for (EMCustomItem item : emItems) {
                    emItemModelMap.computeIfAbsent(item.javaMaterial(), k -> new java.util.HashMap<>())
                            .put(item.customModelData(), item.bedrockTextureKey());
                }
            }
        }

        // Phase 7.2b/maintenance — Build pack + mappings, run drift detection
        if (!emItems.isEmpty()) {
            buildPackAndMappings(emItems);
            evaluateMaintenance();
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

        // Phase 7.3 — reroute Bedrock /em status menu to EM's native MC dialog so
        // Geyser renders it as a native Bedrock form. Requires MC >= 1.21.6.
        // getBukkitVersion() ("1.21.10-R0.1-SNAPSHOT") is always present; McVersions
        // strips the "-R0.1-SNAPSHOT" suffix before comparing.
        boolean mc1216 = de.crazypandas.fmmbedrockbridge.bridge.McVersions
                .isAtLeast(getServer().getBukkitVersion(), 1, 21, 6);
        boolean rerouteCfg = getConfig().getBoolean("phase73.bedrock-dialog-reroute", true);
        if (floodgateAvailable && elitemobsAvailable && mc1216 && rerouteCfg) {
            getServer().getPluginManager().registerEvents(
                    new de.crazypandas.fmmbedrockbridge.bridge.BedrockMenuRerouteListener(this), this);
            log.info("Phase 7.3: Bedrock menu dialog-reroute registered");
        } else {
            log.info("Phase 7.3: reroute NOT registered (floodgate=" + floodgateAvailable
                    + ", em=" + elitemobsAvailable + ", mc>=1.21.6=" + mc1216
                    + ", config=" + rerouteCfg + ")");
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

    private void buildPackAndMappings(java.util.List<EMCustomItem> emItems) {
        java.nio.file.Path packDir = new File(getDataFolder(), "bedrock-pack").toPath();
        mcpackPath = packDir.resolve("em_bridge_pack.mcpack");
        mappingsJsonPath = packDir.resolve("em_bridge_mappings.json");
        currentPackHash = PackHashCalculator.compute(emItems);
        try {
            BedrockItemPackBuilder.build(emItems, currentPackHash, mcpackPath);
            GeyserMappingsWriter.write(emItems, mappingsJsonPath);
            log.info("[BRIDGE] Wrote em_bridge_pack.mcpack ("
                    + emItems.size() + " items) and em_bridge_mappings.json");
        } catch (Exception e) {
            log.warning("[BRIDGE] Failed to build pack/mappings: " + e.getMessage());
        }
    }

    private void evaluateMaintenance() {
        currentEmVersion = pluginVersionOrUnknown("EliteMobs");
        maintenanceTracker = new MaintenanceTracker(getDataFolder().toPath());
        MaintenanceTracker.Result r = maintenanceTracker.evaluate(currentPackHash, currentEmVersion);
        driftActive.set(r.driftActive());

        if (r.firstRun()) {
            log.info("[BRIDGE] Wartung: Erst-Boot — Hash als Baseline gespeichert (" + shortHash(r.currentHash()) + ")");
        } else if (r.driftActive()) {
            log.warning("[BRIDGE] ⚠══════════════════════════════════════════");
            log.warning("[BRIDGE] ⚠ EM-RESOURCE-PACK HAT SICH GEÄNDERT — Proxy-Mappings sind STALE");
            log.warning("[BRIDGE] ⚠ Deployed: " + shortHash(r.deployedHash()) + "  EM " + r.deployedEmVersion());
            log.warning("[BRIDGE] ⚠ Current:  " + shortHash(r.currentHash()) + "  EM " + currentEmVersion);
            log.warning("[BRIDGE] ⚠ Bedrock-Spieler sehen keine Custom-Icons bis Re-Deploy.");
            log.warning("[BRIDGE] ⚠ /fmmbridge maintenance redeploy-instructions  für Schritt-für-Schritt.");
            log.warning("[BRIDGE] ⚠══════════════════════════════════════════");
        } else {
            log.info("[BRIDGE] Wartung: Pack-Hash matched deployed state (kein Drift, " + shortHash(r.currentHash()) + ")");
        }

        getServer().getPluginManager().registerEvents(new OpDriftNotifier(driftActive::get), this);
    }

    private String pluginVersionOrUnknown(String pluginName) {
        var p = getServer().getPluginManager().getPlugin(pluginName);
        return p != null ? p.getDescription().getVersion() : "unknown";
    }

    private static String shortHash(String h) {
        if (h == null || h.isEmpty()) return "(none)";
        return h.length() > 12 ? h.substring(0, 12) + "…" : h;
    }

    public MaintenanceStatusSnapshot getMaintenanceStatus() {
        MaintenanceState s = MaintenanceStateStore.load(getDataFolder().toPath());
        return new MaintenanceStatusSnapshot(
                driftActive.get(),
                currentPackHash,
                s != null ? s.deployedHash() : "(none)",
                s != null ? s.deployedEmVersion() : "(none)",
                s != null ? s.deployedAt().toString() : "(none)");
    }

    public MaintenanceArtifactPaths getMaintenanceArtifactPaths() {
        return new MaintenanceArtifactPaths(
                mcpackPath != null ? mcpackPath.toAbsolutePath().toString() : "(not generated)",
                mappingsJsonPath != null ? mappingsJsonPath.toAbsolutePath().toString() : "(not generated)",
                "/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/packs",
                "/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/custom_mappings"
        );
    }

    public void markMaintenanceDeployed() {
        if (maintenanceTracker != null) {
            maintenanceTracker.markDeployed(currentPackHash, currentEmVersion);
            driftActive.set(false);
        }
    }

    public record MaintenanceStatusSnapshot(
            boolean driftActive,
            String currentHash,
            String deployedHash,
            String deployedEmVersion,
            String deployedAt) {}

    public record MaintenanceArtifactPaths(
            String mcpackPath,
            String mappingsJsonPath,
            String proxyPackTargetDir,
            String proxyMappingsTargetDir) {}
}
