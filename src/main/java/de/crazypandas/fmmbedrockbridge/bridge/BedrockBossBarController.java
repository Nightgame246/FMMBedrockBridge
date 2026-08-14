package de.crazypandas.fmmbedrockbridge.bridge;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Holds a single Bukkit BossBar for one EliteMobs boss, shared across all
 * Bedrock viewers. Title is sourced from FMM's displayName and refreshed during
 * ticks because EliteMobs can set the FMM name a few ticks after the modeled
 * entity is first detected.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>construct → BossBar created (no viewers yet)</li>
 *   <li>addViewer(player) → bossBar.addPlayer(player) + setVisible(true)</li>
 *   <li>tickUpdate() → recompute progress + color from realEntity HP, set if changed</li>
 *   <li>cleanup() → bossBar.removeAll(), viewers cleared</li>
 * </ul>
 *
 * <p>Self-suppression avoidance: our own BOSS_EVENT packets must not be mistaken for the
 * EliteMobs bar we are replacing. The bar's wire UUID is resolved up front via
 * {@link BossBarUuidResolver}; {@link #isOwnUuidResolved()} then reports that the identity is
 * authoritative and the PacketInterceptor stops guessing. Only if resolution fails does it fall
 * back to the legacy first-match heuristic ({@link #hasOwnUuid()} / {@link #registerOwnUuid(UUID)}),
 * which assumes the first title-matching ADD per controller is ours.
 */
public final class BedrockBossBarController {

    private static final double GREEN_THRESHOLD = 0.66;
    private static final double YELLOW_THRESHOLD = 0.33;

    private final UUID realEntityUuid;
    private final LivingEntity realEntity;
    private final Supplier<String> titleSource;
    private volatile String title;
    private final Set<String> titleAliases = ConcurrentHashMap.newKeySet();
    private final BossBar bossBar;
    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();

    /**
     * UUIDs of Bukkit BossBar packets identified as our own. Normally holds exactly one entry,
     * resolved from the Bukkit BossBar itself at construction time ({@link BossBarUuidResolver}).
     * Only when that resolution is unavailable does the PacketInterceptor fall back to filling
     * this set via the legacy first-match heuristic.
     */
    private final Set<UUID> ownUuids = ConcurrentHashMap.newKeySet();

    /**
     * True when {@link #ownUuids} was established authoritatively rather than guessed. In that
     * case the interceptor must never re-claim a different UUID as ours — EliteMobs' pooled bars
     * carry our boss's title too, and claiming one of those would suppress our own bar instead.
     */
    private final boolean ownUuidResolved;

    private double lastProgress = -1.0;
    private BarColor lastColor = null;

    /**
     * Phase 7.1c — combat-state. When false (default outside combat), the BossBar is
     * invisible and {@link #addViewer(Player)} skips {@code bossBar.addPlayer(...)}.
     * When true (during EM combat), the BossBar is visible and viewers are added.
     * Toggled by {@link #enterCombat()} / {@link #exitCombat()} which are called from
     * {@code BedrockCombatTrigger} on EM combat events.
     */
    private boolean isInCombat = false;

    public BedrockBossBarController(LivingEntity realEntity, String initialTitle,
                                    Supplier<String> titleSource) {
        this.realEntityUuid = realEntity.getUniqueId();
        this.realEntity = realEntity;
        this.title = initialTitle;
        this.titleSource = titleSource;
        this.titleAliases.add(initialTitle);
        this.bossBar = Bukkit.createBossBar(initialTitle, BarColor.GREEN, BarStyle.SEGMENTED_10);
        this.bossBar.setProgress(1.0);
        // Phase 7.1c — when combat-enabled, BossBar starts invisible and is only revealed
        // via enterCombat(). When combat-enabled=false (Phase 7.1a fallback), keep the
        // always-visible behavior + treat the controller as permanently in-combat so
        // addViewer triggers bossBar.addPlayer.
        boolean combatEnabled = FMMBedrockBridge.isPhase71cCombatEnabled();
        this.bossBar.setVisible(!combatEnabled);
        this.isInCombat = !combatEnabled;
        this.lastColor = BarColor.GREEN;
        this.lastProgress = 1.0;

        UUID resolved = isUuidResolutionEnabled() ? BossBarUuidResolver.resolve(this.bossBar) : null;
        this.ownUuidResolved = resolved != null;
        if (resolved != null) {
            this.ownUuids.add(resolved);
            FMMBedrockBridge.debugLog("[BRIDGE] Resolved own BossBar UUID " + resolved
                    + " for " + realEntityUuid + " (no heuristic needed)");
        }
    }

    /**
     * Escape hatch for {@code phase71a.resolve-own-bossbar-uuid}. Defaults to true; set to false
     * if a server update ever makes the reflective lookup return the wrong UUID, which would show
     * up as Bedrock players seeing no boss bar at all.
     */
    private static boolean isUuidResolutionEnabled() {
        try {
            return FMMBedrockBridge.getInstance().getConfig()
                    .getBoolean("phase71a.resolve-own-bossbar-uuid", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public UUID getRealEntityUuid() {
        return realEntityUuid;
    }

    public String getTitle() {
        return title;
    }

    public Set<String> getTitleAliases() {
        return Set.copyOf(titleAliases);
    }

    public void addTitleAlias(String alias) {
        if (alias != null && !alias.isEmpty()) {
            titleAliases.add(alias);
        }
    }

    public boolean hasViewer(Player player) {
        return viewers.contains(player);
    }

    /** True if {@code uuid} belongs to a BossBar this controller created. */
    public boolean isOwnUuid(UUID uuid) {
        return ownUuids.contains(uuid);
    }

    /** True if at least one own-UUID has been registered (used by first-match heuristic). */
    public boolean hasOwnUuid() {
        return !ownUuids.isEmpty();
    }

    /**
     * True when our own UUID came from {@link BossBarUuidResolver} rather than the heuristic.
     * The interceptor uses this to refuse any further own-UUID claims.
     */
    public boolean isOwnUuidResolved() {
        return ownUuidResolved;
    }

    /**
     * Called by PacketInterceptor when the legacy heuristic claims a BOSS_EVENT(ADD) UUID as
     * ours. Ignored once the UUID is known authoritatively.
     */
    public void registerOwnUuid(UUID uuid) {
        if (ownUuidResolved) return;
        ownUuids.add(uuid);
    }

    public void addViewer(Player player) {
        if (player == null || !player.isOnline()) return;
        if (viewers.add(player)) {
            // Phase 7.1c — only register with the Bukkit bossBar when in combat. Outside
            // combat the viewer is tracked here but the bossBar stays invisible to them;
            // they're added via enterCombat() when the next combat starts.
            if (isInCombat) {
                refreshTitle();
                bossBar.addPlayer(player);
                bossBar.setVisible(true);
            }
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

        refreshTitle();

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

    private void refreshTitle() {
        if (titleSource == null) return;

        String latest;
        try {
            latest = titleSource.get();
        } catch (Throwable t) {
            return;
        }
        if (latest == null || latest.isEmpty()) return;

        titleAliases.add(latest);
        if (!latest.equals(title)) {
            title = latest;
            bossBar.setTitle(latest);
            FMMBedrockBridge.debugLog("[BRIDGE] Updated BossBar title for " + realEntityUuid
                    + " to '" + latest + "'");
        }
    }

    /**
     * Phase 7.1c — called from {@code BedrockCombatTrigger.onEnterCombat}. Reveals the
     * BossBar and adds all currently-tracked viewers. Idempotent — safe to call when
     * already in combat.
     */
    public void enterCombat() {
        if (isInCombat) return;
        isInCombat = true;
        refreshTitle();
        bossBar.setVisible(true);
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) {
                bossBar.addPlayer(viewer);
            }
        }
    }

    /**
     * Phase 7.1c — called from {@code BedrockCombatTrigger.onExitCombat}. Hides the
     * BossBar from all viewers. Idempotent.
     *
     * <p>Own-UUIDs are deliberately <b>not</b> cleared here. {@link #bossBar} is created once in
     * the constructor and reused for the controller's whole life, so its wire UUID never changes
     * between combats — dropping the claim only forced the heuristic to re-race EliteMobs at every
     * combat-enter, and since EM 10.8.0 pools and re-titles its bars, that race is one EM can win.
     * The claim is released in {@link #cleanup()} together with the bar itself.
     */
    public void exitCombat() {
        if (!isInCombat) return;
        isInCombat = false;
        bossBar.removeAll();
        bossBar.setVisible(false);
    }

    /** Phase 7.1c — used by {@code /fmmbridge debug} and the lazy-add gate in addViewer. */
    public boolean isInCombat() {
        return isInCombat;
    }

    public void cleanup() {
        bossBar.removeAll();
        viewers.clear();
        isInCombat = false;
        if (!ownUuidResolved) {
            // Heuristic claims are per-lifetime guesses; drop them with the controller.
            // A resolved UUID stays — it identifies this exact bar object, which the
            // interceptor may still see packets for while cleanup propagates.
            ownUuids.clear();
        }
    }
}
