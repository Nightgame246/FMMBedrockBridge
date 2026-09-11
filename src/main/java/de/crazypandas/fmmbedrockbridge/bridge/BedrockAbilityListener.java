package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 7.4 — maps sneak-based input onto EliteMobs' three ability slots for Bedrock players.
 *
 * <p>EliteMobs binds them to an F-chord; Bedrock has no offhand-swap control, so those players
 * cannot trigger a single ability. See docs/upstream-bugs/em-advanced-combat-bedrock-input-lockout.md
 *
 * <p>Java players are never touched — they keep EliteMobs' original scheme.
 */
public final class BedrockAbilityListener implements Listener {

    private final AdvancedCombatHook hook;
    private final long maxOpenTicks;
    private final boolean requireCombat;
    private final boolean feedback;

    private final Map<UUID, BedrockAbilityGesture> gestures = new ConcurrentHashMap<>();

    public BedrockAbilityListener(AdvancedCombatHook hook, long maxOpenTicks,
                                  boolean requireCombat, boolean feedback) {
        this.hook = hook;
        this.maxOpenTicks = maxOpenTicks;
        this.requireCombat = requireCombat;
        this.feedback = feedback;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        // Only sneak STARTS count. Closing on sneak end would make "sneak, sneak" impossible,
        // because the second start requires releasing first.
        if (!event.isSneaking()) return;

        Player player = event.getPlayer();
        if (!armed(player)) return;

        dispatch(player, gestureFor(player).sneakStart(Bukkit.getCurrentTick()), null);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!armed(player)) return;

        BedrockAbilityGesture.Outcome outcome = gestureFor(player).attack(Bukkit.getCurrentTick());
        // Cancel the swing that opened the chord, otherwise the player also hits.
        dispatch(player, outcome, () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!armed(player)) return;

        BedrockAbilityGesture.Outcome outcome = gestureFor(player).use(Bukkit.getCurrentTick());
        // Cancel so the player does not also place a block or open a container.
        dispatch(player, outcome, () -> event.setCancelled(true));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gestures.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        closeFor(event.getEntity());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        closeFor(event.getPlayer());
    }

    private void closeFor(Player player) {
        BedrockAbilityGesture gesture = gestures.get(player.getUniqueId());
        if (gesture != null) gesture.close();
    }

    /** Bedrock-only, and only while EliteMobs considers the player to be in combat. */
    private boolean armed(Player player) {
        if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return false;
        return !requireCombat || hook.isInCombat(player);
    }

    private BedrockAbilityGesture gestureFor(Player player) {
        return gestures.computeIfAbsent(player.getUniqueId(),
                uuid -> new BedrockAbilityGesture(maxOpenTicks));
    }

    private void dispatch(Player player, BedrockAbilityGesture.Outcome outcome, Runnable consumeInput) {
        if (outcome == BedrockAbilityGesture.Outcome.NONE) return;
        if (consumeInput != null) consumeInput.run();

        String message = hook.fire(player, outcome);
        FMMBedrockBridge.debugLog("[PHASE74] " + player.getName() + " -> " + outcome);
        if (feedback && message != null) {
            player.sendActionBar(message);
        }
    }
}
