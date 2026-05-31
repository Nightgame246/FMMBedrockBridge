package de.crazypandas.fmmbedrockbridge.commands;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockBossBarController;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockEntityBridge;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockNametagController;
import de.crazypandas.fmmbedrockbridge.bridge.FMMEntityData;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FMMBridgeCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;

    public FMMBridgeCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("fmmbedrockbridge.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge <debug|maintenance>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "debug" -> runDebug(sender);
            case "maintenance" -> runMaintenance(sender, args);
            default -> sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge <debug|maintenance>");
        }
        return true;
    }

    private void runMaintenance(CommandSender sender, String[] args) {
        FMMBedrockBridge plugin = FMMBedrockBridge.getInstance();
        if (args.length < 2) {
            sender.sendMessage("§6Wartung §7— Subcommands: §estatus§7, §eredeploy-instructions§7, §emark-deployed");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "status" -> showMaintenanceStatus(sender, plugin);
            case "redeploy-instructions" -> showRedeployInstructions(sender, plugin);
            case "mark-deployed" -> markDeployed(sender, plugin);
            default -> sender.sendMessage("§cUnbekannter Subcommand: " + args[1]);
        }
    }

    private void showMaintenanceStatus(CommandSender sender, FMMBedrockBridge plugin) {
        var status = plugin.getMaintenanceStatus();
        sender.sendMessage("§6── Wartungs-Status ──");
        sender.sendMessage("§7Drift aktiv: " + (status.driftActive() ? "§cJA — Re-Deploy nötig" : "§aNein — alles im Lot"));
        sender.sendMessage("§7Aktueller Pack-Hash: §f" + shortHash(status.currentHash()));
        sender.sendMessage("§7Deployed-Hash:        §f" + shortHash(status.deployedHash()));
        sender.sendMessage("§7Deployed EM-Version:  §f" + status.deployedEmVersion());
        sender.sendMessage("§7Deployed am:          §f" + status.deployedAt());
        if (status.driftActive()) {
            sender.sendMessage("§7Nächster Schritt: §e/fmmbridge maintenance redeploy-instructions");
        }
    }

    private void showRedeployInstructions(CommandSender sender, FMMBedrockBridge plugin) {
        var paths = plugin.getMaintenanceArtifactPaths();
        sender.sendMessage("§6── Re-Deploy-Anleitung ──");
        sender.sendMessage("§71. SCP Mini-Pack auf Proxy01:");
        sender.sendMessage("§f   scp " + paths.mcpackPath() + " amp@<proxy>:" + paths.proxyPackTargetDir() + "/");
        sender.sendMessage("§72. SCP Geyser-Mappings auf Proxy01:");
        sender.sendMessage("§f   scp " + paths.mappingsJsonPath() + " amp@<proxy>:" + paths.proxyMappingsTargetDir() + "/");
        sender.sendMessage("§73. Proxy01 neu starten (Geyser scannt mappings beim Boot)");
        sender.sendMessage("§74. Auf Backend: §e/fmmbridge maintenance mark-deployed");
    }

    private void markDeployed(CommandSender sender, FMMBedrockBridge plugin) {
        plugin.markMaintenanceDeployed();
        sender.sendMessage("§a✓ Aktueller Pack-Hash als deployed markiert. Drift-Flag gelöscht.");
    }

    private String shortHash(String hash) {
        if (hash == null) return "(none)";
        return hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
    }

    private void runDebug(CommandSender sender) {
        BedrockEntityBridge bridge = FMMBedrockBridge.getInstance().getBridge();
        if (bridge == null) {
            sender.sendMessage("§c[FMMBridge] Bridge not initialized.");
            return;
        }

        Map<ModeledEntity, FMMEntityData> entities = bridge.getEntityDataMap();
        sender.sendMessage("§6[FMMBridge Debug] §7Tracked entities: §e" + entities.size());

        for (Map.Entry<ModeledEntity, FMMEntityData> entry : entities.entrySet()) {
            ModeledEntity me = entry.getKey();
            FMMEntityData data = entry.getValue();

            String alive = data.isAlive() ? "§aalive" : "§cdead";
            String destroyed = data.isDestroyed() ? " §c[DESTROYED]" : "";
            int viewers = data.getViewers().size();

            Location loc = data.getLocation();
            String locStr = loc == null ? "null" :
                    loc.getWorld().getName() + " " +
                    String.format("%.0f,%.0f,%.0f", loc.getX(), loc.getY(), loc.getZ());

            sender.sendMessage("  §a" + me.getEntityID()
                    + " §8|§7 " + alive + destroyed
                    + " §8|§7 viewers=§e" + viewers
                    + " §8|§7 " + locStr);
        }

        sender.sendMessage("§6[FMMBridge Debug] §7Ready Bedrock players: §e"
                + bridge.getViewerManager().getReadyPlayers().size());
        for (Player p : bridge.getViewerManager().getReadyPlayers()) {
            Location pl = p.getLocation();
            sender.sendMessage("  §b" + p.getName()
                    + " §8|§7 " + pl.getWorld().getName()
                    + " " + String.format("%.0f,%.0f,%.0f", pl.getX(), pl.getY(), pl.getZ()));
        }

        Map<UUID, BedrockBossBarController> controllers = bridge.getActiveControllers();
        boolean debug = FMMBedrockBridge.isDebugEnabled();
        sender.sendMessage("§6[FMMBridge Debug] §7BossBar controllers: §e" + controllers.size()
                + " §8|§7 EM-suppressed UUIDs: §e" + bridge.getBossBarRegistry().size()
                + " §8|§7 debug-mode: " + (debug ? "§aon" : "§7off"));
        for (BedrockBossBarController ctrl : controllers.values()) {
            sender.sendMessage("  §d" + ctrl.getTitle()
                    + " §8|§7 own-claimed: " + (ctrl.hasOwnUuid() ? "§ayes" : "§7no")
                    + " §8|§7 inCombat: " + (ctrl.isInCombat() ? "§ayes" : "§7no")
                    + " §8|§7 entityUuid=§7" + ctrl.getRealEntityUuid().toString().substring(0, 8) + "…");
        }

        Map<UUID, BedrockNametagController> nametags = bridge.getActiveNametags();
        sender.sendMessage("§6[FMMBridge Debug] §7Nametag controllers: §e" + nametags.size());
        for (BedrockNametagController nt : nametags.values()) {
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(nt.getCurrentText());
            sender.sendMessage("  §d" + text
                    + " §8|§7 textDisplayId=§7" + nt.getTextDisplayEntityId()
                    + " §8|§7 inCombat: " + (nt.isInCombat() ? "§ayes" : "§7no")
                    + " §8|§7 entityUuid=§7" + nt.getRealEntityUuid().toString().substring(0, 8) + "…");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("debug", "maintenance");
        if (args.length == 2 && args[0].equalsIgnoreCase("maintenance"))
            return List.of("status", "redeploy-instructions", "mark-deployed");
        return List.of();
    }
}
