package de.crazypandas.fmmbedrockbridge.bridge;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Phase 7.4 — publishes our ability feedback through EliteMobs' own action-bar compositor.
 *
 * <p><b>Why not {@code player.sendActionBar()}.</b> Since EliteMobs 10.9.0 the action bar has a
 * single owner. {@code AdvancedCombatRuntime} renders every tick, keeps {@code CLASS_HUD}
 * published permanently and re-sends a keepalive every 40 ticks — and it re-renders immediately
 * whenever the player's health changes, which in combat is constantly. A direct write is
 * therefore overwritten within a tick or two and the player sees at most a flicker. That is why
 * {@code phase74.feedback} had to ship disabled.
 *
 * <p>The compositor arbitrates by priority instead of by who wrote last. {@code ABILITY_INPUT}
 * sits at 310 against the HUD's 100 and expires after 40 ticks, which is precisely the case its
 * own class documentation describes: <i>"combat feedback can cover it temporarily without
 * destroying it"</i>.
 *
 * <p><b>Resolved by name, never imported.</b> Same firewall as {@link AdvancedCombatSupport}:
 * naming the class in an import would link it at class-load time, so a rename upstream would
 * take the listener down with it. Here it degrades to a direct write instead — the old
 * behaviour, which is wrong but harmless. Verified against the 10.9.0 artifact with {@code javap}:
 * {@code public static void show(Player, Source, String)} and the constant {@code ABILITY_INPUT}
 * both exist.
 */
public final class EliteMobsActionBar {

    private static final String COMPOSITOR_CLASS =
            "com.magmaguy.elitemobs.presentation.actionbar.ActionBarCompositor";
    private static final String SOURCE_CLASS = COMPOSITOR_CLASS + "$Source";
    private static final String SOURCE_CONSTANT = "ABILITY_INPUT";

    private static boolean resolutionAttempted;
    private static Method showMethod;
    private static Object abilityInputSource;
    private static String unavailableReason = "not probed yet";

    private EliteMobsActionBar() {
    }

    /** True when the compositor and our source constant both resolved. */
    public static synchronized boolean isAvailable() {
        resolve();
        return showMethod != null && abilityInputSource != null;
    }

    /** Why the last {@link #isAvailable()} call said no — for the startup log. */
    public static synchronized String unavailableReason() {
        resolve();
        return unavailableReason;
    }

    /**
     * Shows {@code message} to {@code player}, through the compositor where possible.
     *
     * <p>Safe off the main thread: the compositor queues calls made from another thread and
     * drains them in its own task. The fallback path is a plain Bukkit call, so callers should
     * still be on the main thread — which every caller in this plugin is, being a Bukkit event.
     */
    public static void show(Player player, String message) {
        if (player == null || message == null || message.isEmpty()) return;
        if (isAvailable()) {
            try {
                showMethod.invoke(null, player, abilityInputSource, message);
                return;
            } catch (Throwable t) {
                // A resolved method that fails at call time means the signature moved under us.
                // Drop back to the direct write permanently rather than retry per input.
                synchronized (EliteMobsActionBar.class) {
                    showMethod = null;
                    abilityInputSource = null;
                    unavailableReason = "show() failed at call time (" + t.getClass().getSimpleName() + ")";
                }
                de.crazypandas.fmmbedrockbridge.FMMBedrockBridge.debugLog(
                        "[PHASE74] compositor call failed, falling back to sendActionBar: " + t);
            }
        }
        player.sendActionBar(message);
    }

    private static void resolve() {
        if (resolutionAttempted) return;
        resolutionAttempted = true;
        try {
            ClassLoader loader = EliteMobsActionBar.class.getClassLoader();
            Class<?> sourceClass = Class.forName(SOURCE_CLASS, false, loader);
            Class<?> compositorClass = Class.forName(COMPOSITOR_CLASS, false, loader);

            Object source = enumConstant(sourceClass, SOURCE_CONSTANT);
            Method show = compositorClass.getMethod("show", Player.class, sourceClass, String.class);

            abilityInputSource = source;
            showMethod = show;
            unavailableReason = "";
        } catch (Throwable t) {
            showMethod = null;
            abilityInputSource = null;
            unavailableReason = t.getClass().getSimpleName() + " resolving " + COMPOSITOR_CLASS
                    + "." + SOURCE_CONSTANT;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> sourceClass, String name) {
        return Enum.valueOf((Class<Enum>) sourceClass, name);
    }
}
