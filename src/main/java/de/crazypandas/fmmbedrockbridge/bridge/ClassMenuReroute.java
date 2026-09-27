package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Phase 7.3c — EliteMobs' class menu as a dialog (Geyser: a native form) for Bedrock players.
 *
 * <p>EliteMobs builds every page of the class menu twice, as a dialog and as a chest, and picks
 * one per call in {@code ClassMenuCoordinator.renderer(player)}:
 * {@code supportsDialogs(player) ? dialogs : inventories}. {@code supportsDialogs} is false for
 * every Bedrock player, so they always get the chest — although Geyser renders dialogs fine, the
 * same situation Phase 7.3 and 7.3b solve for the status and quest menus.
 *
 * <p><b>Why not cancel the chest like 7.3 does.</b> The class menu has an overview, a page per
 * class, a controls page, an NPC trainer entry and a button action behind every item, all of
 * which re-render through that one switch. Rerouting there covers all of them at once, without
 * matching titles and without a chest flashing up first. So the coordinator's {@code inventories}
 * renderer is replaced by a stand-in that hands Bedrock players to {@code dialogs} and everyone
 * else to the original chest renderer.
 *
 * <p><b>Firewall.</b> Everything is resolved by name, like {@link AdvancedCombatSupport}: the
 * package is EliteMobs-internal. Verified with {@code javap} against the 10.9.0 and 10.9.5
 * artifacts — {@code ClassSelectionMenu.COORDINATOR}, the coordinator's {@code dialogs} and
 * {@code inventories} fields and the four-method {@code ClassMenuRenderer} interface are identical
 * in both. If anything is missing, {@link #install} reports why and changes nothing.
 * {@link #uninstall} puts the original renderer back.
 */
public final class ClassMenuReroute {

    private static final String PKG = "com.magmaguy.elitemobs.advancedcombat.menu.";

    private final Predicate<Player> isBedrock;

    private Object coordinator;
    private Field inventoriesField;
    private Object originalInventories;
    private String unavailableReason = "not installed";

    public ClassMenuReroute(Predicate<Player> isBedrock) {
        this.isBedrock = isBedrock;
    }

    /** @return true when the stand-in is in place */
    public boolean install() {
        try {
            ClassLoader loader = ClassMenuReroute.class.getClassLoader();
            Class<?> menuClass = Class.forName(PKG + "ClassSelectionMenu", true, loader);
            Class<?> coordinatorClass = Class.forName(PKG + "ClassMenuCoordinator", false, loader);
            Class<?> rendererClass = Class.forName(PKG + "ClassMenuRenderer", false, loader);

            Field coordinatorField = menuClass.getDeclaredField("COORDINATOR");
            coordinatorField.setAccessible(true);
            Object coord = coordinatorField.get(null);

            Field dialogsField = coordinatorClass.getDeclaredField("dialogs");
            Field inventories = coordinatorClass.getDeclaredField("inventories");
            dialogsField.setAccessible(true);
            inventories.setAccessible(true);
            Object dialogs = dialogsField.get(coord);
            Object chests = inventories.get(coord);
            if (dialogs == null || chests == null) {
                unavailableReason = "renderer not initialised";
                return false;
            }

            Object standIn = Proxy.newProxyInstance(rendererClass.getClassLoader(),
                    new Class<?>[]{rendererClass}, new Router(dialogs, chests));
            inventories.set(coord, standIn);

            coordinator = coord;
            inventoriesField = inventories;
            originalInventories = chests;
            unavailableReason = "";
            return true;
        } catch (Throwable t) {
            unavailableReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    public void uninstall() {
        if (coordinator == null) return;
        try {
            inventoriesField.set(coordinator, originalInventories);
        } catch (Throwable t) {
            FMMBedrockBridge.getInstance().getLogger()
                    .warning("[Phase7.3c] could not restore EliteMobs' class-menu renderer: " + t);
        }
        coordinator = null;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    /** Asked on every render, so the config switch works without a restart. */
    private boolean useDialog(Player player) {
        return RerouteDecision.classMenuUsesDialog(
                FMMBedrockBridge.isPhase73ClassRerouteEnabled(),
                isBedrock.test(player),
                EliteMobsMenuSettings.useBookMenus(player.getUniqueId()),
                EliteMobsMenuSettings.onlyUseBedrockMenus(),
                true); // install() only runs on MC >= 1.21.6
    }

    private final class Router implements InvocationHandler {
        private final Object dialogs;
        private final Object chests;

        Router(Object dialogs, Object chests) {
            this.dialogs = dialogs;
            this.chests = chests;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "FMMBedrockBridge class-menu router";
                default: break;
            }
            Object target = chests;
            if (args != null && args.length > 0 && args[0] instanceof Player player) {
                try {
                    if (useDialog(player)) {
                        target = dialogs;
                        FMMBedrockBridge.debugLog("[Phase7.3c] " + player.getName() + " "
                                + method.getName() + " -> dialog");
                    }
                } catch (Throwable t) {
                    // Deciding must never cost the player their menu: fall back to the chest.
                    FMMBedrockBridge.debugLog("[Phase7.3c] decision failed, chest: " + t);
                }
            }
            // The interface is package-private in EliteMobs.
            method.setAccessible(true);
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /** EliteMobs' menu-style settings, by name — both are plain static getters. */
    static final class EliteMobsMenuSettings {
        private EliteMobsMenuSettings() {}

        static boolean useBookMenus(UUID uuid) {
            try {
                Class<?> c = Class.forName("com.magmaguy.elitemobs.playerdata.database.PlayerData");
                return (boolean) c.getMethod("getUseBookMenus", UUID.class).invoke(null, uuid);
            } catch (Throwable t) {
                return false; // unknown -> leave EliteMobs' chest alone
            }
        }

        static boolean onlyUseBedrockMenus() {
            try {
                Class<?> c = Class.forName("com.magmaguy.elitemobs.config.DefaultConfig");
                return (boolean) c.getMethod("isOnlyUseBedrockMenus").invoke(null);
            } catch (Throwable t) {
                return true; // unknown -> leave EliteMobs' chest alone
            }
        }
    }
}
