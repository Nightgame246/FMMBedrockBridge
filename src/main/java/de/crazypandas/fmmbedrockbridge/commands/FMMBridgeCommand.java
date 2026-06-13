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
            sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge <debug>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "debug" -> runDebug(sender);
            default -> sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge <debug>");
        }
        return true;
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
        if (!sender.hasPermission("fmmbedrockbridge.admin")) return List.of();
        if (args.length == 1) return List.of("debug");
        return List.of();
    }
}
