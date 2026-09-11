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

    private final Map<UUID, BedrockAbilityGesture> gestures = new ConcurrentHashMap<>();

    public BedrockAbilityListener(AdvancedCombatHook hook) {
        this.hook = hook;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        // Only sneak STARTS count. Closing on sneak end would make "sneak, sneak" impossible,
        // because the second start requires releasing first.
        if (!event.isSneaking()) return;

        Player player = event.getPlayer();
        if (!armed(player)) {
            FMMBedrockBridge.debugLog("[PHASE74] sneak from " + player.getName() + " — not armed");
            return;
        }

        BedrockAbilityGesture.Outcome outcome = gestureFor(player).sneakStart(Bukkit.getCurrentTick());
        if (outcome == BedrockAbilityGesture.Outcome.NONE) {
            FMMBedrockBridge.debugLog("[PHASE74] chord OPEN for " + player.getName());
        }
        dispatch(player, outcome, null);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!armed(player)) return;

        BedrockAbilityGesture.Outcome outcome =
                gestureFor(player).attack(Bukkit.getCurrentTick(), player.isSneaking());
        if (outcome == BedrockAbilityGesture.Outcome.NONE) {
            FMMBedrockBridge.debugLog("[PHASE74] attack from " + player.getName()
                    + " — no open chord (sneaking=" + player.isSneaking() + ")");
        }
        // Cancel the swing that opened the chord, otherwise the player also hits.
        dispatch(player, outcome, () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!leftClick && !rightClick) return;

        Player player = event.getPlayer();
        if (!armed(player)) return;

        // EliteMobs binds SIGNATURE to the left click itself, not to landing a hit: its
        // onChordInteract routes LEFT_CLICK_AIR and LEFT_CLICK_BLOCK to leftClick(). Swinging at
        // thin air has to work, exactly like UTILITY does. The EntityDamageByEntityEvent handler
        // stays as the second path, for the case where the swing actually connects with a mob.
        BedrockAbilityGesture gesture = gestureFor(player);
        long tick = Bukkit.getCurrentTick();
        BedrockAbilityGesture.Outcome outcome = leftClick
                ? gesture.attack(tick, player.isSneaking())
                : gesture.use(tick, player.isSneaking());
        if (outcome == BedrockAbilityGesture.Outcome.NONE) {
            FMMBedrockBridge.debugLog("[PHASE74] " + (leftClick ? "left" : "right") + " click from "
                    + player.getName() + " — no open chord (sneaking=" + player.isSneaking() + ")");
        }
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

    /**
     * Bedrock-only, and only while EliteMobs itself would act on the input — see
     * {@link AdvancedCombatHook#canUseAbilities(Player)}. Floodgate first: it is the cheapest
     * gate and rules out every Java player before EliteMobs is touched at all.
     */
    private boolean armed(Player player) {
        if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return false;
        return !FMMBedrockBridge.isPhase74RequireCombat() || hook.canUseAbilities(player);
    }

    /**
     * Config is read per event like in every other phase, so a {@code chord-max-ticks} change
     * takes effect on the next reload instead of the next server restart. A gesture keeps the
     * value it was built with, so one whose window no longer matches the configured value is
     * replaced — an open chord is dropped in that moment, which is the right call after a
     * deliberate config change.
     */
    private BedrockAbilityGesture gestureFor(Player player) {
        long maxOpenTicks = FMMBedrockBridge.getPhase74ChordMaxTicks();
        return gestures.compute(player.getUniqueId(), (uuid, existing) ->
                existing != null && existing.maxOpenTicks() == maxOpenTicks
                        ? existing
                        : new BedrockAbilityGesture(maxOpenTicks));
    }

    /**
     * Consumes the triggering event ONLY when EliteMobs actually took the input — otherwise the
     * player would lose the swing or the interaction and get nothing in return.
     */
    private void dispatch(Player player, BedrockAbilityGesture.Outcome outcome, Runnable consumeInput) {
        if (outcome == BedrockAbilityGesture.Outcome.NONE) return;

        AdvancedCombatHook.FireResult result = hook.fire(player, outcome);
        FMMBedrockBridge.debugLog("[PHASE74] " + player.getName() + " -> " + outcome
                + " (handled=" + result.handled() + ")");
        if (!result.handled()) return;

        if (consumeInput != null) consumeInput.run();
        if (FMMBedrockBridge.isPhase74FeedbackEnabled() && result.message() != null) {
            player.sendActionBar(result.message());
        }
    }
}
