package de.crazypandas.fmmbedrockbridge.commands;

import de.crazypandas.fmmbedrockbridge.converter.BedrockModelConverter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public class FMMBridgeCommand implements CommandExecutor, TabCompleter {

    private final BedrockModelConverter converter;

    public FMMBridgeCommand(BedrockModelConverter converter) {
        this.converter = converter;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("fmmbedrockbridge.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge convert [modelId|all]");
            return true;
        }

        if (args[0].equalsIgnoreCase("convert")) {
            if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
                sender.sendMessage("§6[FMMBridge] §7Converting all models...");
                converter.convertAll();
                sender.sendMessage("§6[FMMBridge] §aDone! Check server logs for details.");
            } else {
                String modelId = args[1];
                sender.sendMessage("§6[FMMBridge] §7Converting model: " + modelId);
                try {
                    converter.convert(modelId);
                    sender.sendMessage("§6[FMMBridge] §aDone! Output: plugins/FMMBedrockBridge/bedrock-skins/" + modelId + "/");
                } catch (Exception e) {
                    sender.sendMessage("§c[FMMBridge] Error: " + e.getMessage());
                }
            }
            return true;
        }

        sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge convert [modelId|all]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("convert");
        if (args.length == 2 && args[0].equalsIgnoreCase("convert")) return List.of("all");
        return List.of();
    }
}
