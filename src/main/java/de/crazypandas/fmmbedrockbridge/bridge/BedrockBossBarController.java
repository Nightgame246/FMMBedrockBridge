package de.crazypandas.fmmbedrockbridge.bridge;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a single Bukkit BossBar for one EliteMobs boss, shared across all
 * Bedrock viewers. Title is set once at construction and never updated —
 * EliteMobs phase-name changes are out of scope for Phase 7.1a.
 *
 * Lifecycle:
 *  - construct → BossBar created (no viewers yet)
 *  - addViewer(player) → bossBar.addPlayer(player)
 *  - tickUpdate() → recompute progress + color from realEntity HP, set if changed
 *  - cleanup() → bossBar.removeAll(), viewers cleared
 */
public final class BedrockBossBarController {

    private static final double GREEN_THRESHOLD = 0.66;
    private static final double YELLOW_THRESHOLD = 0.33;

    private final UUID realEntityUuid;
    private final LivingEntity realEntity;
    private final String title;
    private final BossBar bossBar;
    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();

    private double lastProgress = -1.0;
    private BarColor lastColor = null;

    public BedrockBossBarController(LivingEntity realEntity, String title) {
        this.realEntityUuid = realEntity.getUniqueId();
        this.realEntity = realEntity;
        this.title = title;
        this.bossBar = Bukkit.createBossBar(title, BarColor.GREEN, BarStyle.SEGMENTED_10);
        this.bossBar.setProgress(1.0);
        this.lastColor = BarColor.GREEN;
        this.lastProgress = 1.0;
    }

    public UUID getRealEntityUuid() {
        return realEntityUuid;
    }

    public String getTitle() {
        return title;
    }

    public boolean hasViewer(Player player) {
        return viewers.contains(player);
    }

    public void addViewer(Player player) {
        if (player == null || !player.isOnline()) return;
        if (viewers.add(player)) {
            bossBar.addPlayer(player);
        }
    }

    public void removeViewer(Player player) {
        if (viewers.remove(player)) {
            bossBar.removePlayer(player);
        }
    }

    /** Recomputes progress + color from realEntity HP. Skipped if entity is dead/invalid. */
    public void tickUpdate() {
        if (realEntity == null || realEntity.isDead() || !realEntity.isValid()) return;

        double max = realEntity.getMaxHealth();
        if (max <= 0) return;
        double progress = Math.max(0.0, Math.min(1.0, realEntity.getHealth() / max));

        if (progress != lastProgress) {
            bossBar.setProgress(progress);
            lastProgress = progress;
        }

        BarColor color;
        if (progress >= GREEN_THRESHOLD) color = BarColor.GREEN;
        else if (progress >= YELLOW_THRESHOLD) color = BarColor.YELLOW;
        else color = BarColor.RED;

        if (color != lastColor) {
            bossBar.setColor(color);
            lastColor = color;
        }
    }

    public void cleanup() {
        bossBar.removeAll();
        viewers.clear();
    }
}
