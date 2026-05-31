package de.crazypandas.fmmbedrockbridge.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.function.Supplier;

public final class OpDriftNotifier implements Listener {

    private final Supplier<Boolean> driftActive;

    public OpDriftNotifier(Supplier<Boolean> driftActive) {
        this.driftActive = driftActive;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().isOp()) return;
        if (!driftActive.get()) return;

        Component header = Component.text("⚠ FMMBedrockBridge — Wartung erforderlich", NamedTextColor.GOLD, TextDecoration.BOLD);
        Component line1 = Component.text("Das EliteMobs-Resource-Pack hat sich geändert, die Bridge-Mappings auf dem Proxy sind nicht mehr aktuell.", NamedTextColor.YELLOW);
        Component line2 = Component.text("Bedrock-Spieler sehen aktuell keine Custom-Icons (BagOfCoin etc.).", NamedTextColor.YELLOW);
        Component button = Component.text("[ Details + Re-Deploy-Anleitung ]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand("/fmmbridge maintenance status"))
                .hoverEvent(HoverEvent.showText(Component.text("Klick öffnet /fmmbridge maintenance status", NamedTextColor.GRAY)));

        event.getPlayer().sendMessage(Component.empty());
        event.getPlayer().sendMessage(header);
        event.getPlayer().sendMessage(line1);
        event.getPlayer().sendMessage(line2);
        event.getPlayer().sendMessage(button);
        event.getPlayer().sendMessage(Component.empty());
    }
}
