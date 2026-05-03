package de.crazypandas.fmmbedrockbridge.commands;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockBossBarController;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockNametagController;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockEntityBridge;
import de.crazypandas.fmmbedrockbridge.bridge.IBridgeEntityData;
import de.crazypandas.fmmbedrockbridge.converter.BedrockModelConverter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FMMBridgeCommand implements CommandExecutor, TabCompleter {

    private final BedrockModelConverter converter;
    private final Plugin plugin;

    public FMMBridgeCommand(BedrockModelConverter converter, Plugin plugin) {
        this.converter = converter;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("fmmbedrockbridge.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge <convert|debug>");
            return true;
        }

        if (args[0].equalsIgnoreCase("convert")) {
            if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
                sender.sendMessage("§6[FMMBridge] §7Converting all models...");
                Map<String, FileModelConverter> models = new HashMap<>(FileModelConverter.getConvertedFileModels());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    converter.convertAll(models);
                    Bukkit.getScheduler().runTask(plugin,
                            () -> sender.sendMessage("§6[FMMBridge] §aDone! Check server logs for details."));
                });
            } else {
                String modelId = args[1];
                sender.sendMessage("§6[FMMBridge] §7Converting model: " + modelId);
                Map<String, FileModelConverter> models = new HashMap<>(FileModelConverter.getConvertedFileModels());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        converter.convert(modelId, models);
                        Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage("§6[FMMBridge] §aDone! Output: plugins/FMMBedrockBridge/bedrock-skins/" + modelId + "/"));
                    } catch (Exception e) {
                        Bukkit.getScheduler().runTask(plugin,
                                () -> sender.sendMessage("§c[FMMBridge] Error: " + e.getMessage()));
                    }
                });
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("debug")) {
            runDebug(sender);
            return true;
        }

        sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge <convert|debug>");
        return true;
    }

    private void runDebug(CommandSender sender) {
        BedrockEntityBridge bridge = FMMBedrockBridge.getInstance().getBridge();
        if (bridge == null) {
            sender.sendMessage("§c[FMMBridge] Bridge not initialized.");
            return;
        }

        Map<ModeledEntity, IBridgeEntityData> entities = bridge.getEntityDataMap();
        sender.sendMessage("§6[FMMBridge Debug] §7Tracked entities: §e" + entities.size());

        for (Map.Entry<ModeledEntity, IBridgeEntityData> entry : entities.entrySet()) {
            ModeledEntity me = entry.getKey();
            IBridgeEntityData data = entry.getValue();

            String type = (me instanceof DynamicEntity) ? "§aDYNAMIC" : "§bSTATIC";
            String alive = data.isAlive() ? "§aalive" : "§cdead";
            String destroyed = data.isDestroyed() ? " §c[DESTROYED]" : "";
            int viewers = data.getViewers().size();
            int fakeId = data.getPacketEntity().getEntityId();

            Location loc = data.getLocation();
            String locStr = loc == null ? "null" :
                    loc.getWorld().getName() + " " +
                    String.format("%.0f,%.0f,%.0f", loc.getX(), loc.getY(), loc.getZ());

            sender.sendMessage("  " + type + " §7" + me.getEntityID()
                    + " §8|§7 " + alive + destroyed
                    + " §8|§7 viewers=§e" + viewers
                    + " §8|§7 fakeId=§7" + fakeId
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

        // Phase 7.1a — BossBar subsystem state
        Map<java.util.UUID, BedrockBossBarController> controllers = bridge.getActiveControllers();
        boolean debug = FMMBedrockBridge.isDebugEnabled();
        sender.sendMessage("§6[FMMBridge Debug] §7BossBar controllers: §e" + controllers.size()
                + " §8|§7 EM-suppressed UUIDs: §e" + bridge.getBossBarRegistry().size()
                + " §8|§7 debug-mode: " + (debug ? "§aon" : "§7off"));
        for (BedrockBossBarController ctrl : controllers.values()) {
            sender.sendMessage("  §d" + ctrl.getTitle()
                    + " §8|§7 own-claimed: " + (ctrl.hasOwnUuid() ? "§ayes" : "§7no")
                    + " §8|§7 entityUuid=§7" + ctrl.getRealEntityUuid().toString().substring(0, 8) + "…");
        }

        // Phase 7.1b — Nametag subsystem state
        Map<java.util.UUID, BedrockNametagController> nametags = bridge.getActiveNametags();
        sender.sendMessage("§6[FMMBridge Debug] §7Nametag controllers: §e" + nametags.size());
        for (BedrockNametagController nt : nametags.values()) {
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(nt.getCurrentText());
            sender.sendMessage("  §d" + text
                    + " §8|§7 textDisplayId=§7" + nt.getTextDisplayEntityId()
                    + " §8|§7 entityUuid=§7" + nt.getRealEntityUuid().toString().substring(0, 8) + "…");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("convert", "debug");
        if (args.length == 2 && args[0].equalsIgnoreCase("convert")) return List.of("all");
        return List.of();
    }
}
