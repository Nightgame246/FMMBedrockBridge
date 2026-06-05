package de.crazypandas.fmmbedrockbridge.elite;

import com.magmaguy.elitemobs.entitytracker.EntityTracker;
import com.magmaguy.elitemobs.mobconstructor.EliteEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Soft-dependency wrapper around EliteMobs API. The only class in this codebase
 * that imports {@code com.magmaguy.elitemobs.*}. All public methods return null
 * (or false) when EliteMobs is not installed or its API broke between versions —
 * callers must null-check rather than relying on exceptions.
 */
public final class EliteMobsHook {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    private static final boolean PLUGIN_PRESENT;
    private static volatile boolean apiBroken = false;

    static {
        PLUGIN_PRESENT = Bukkit.getPluginManager().getPlugin("EliteMobs") != null;
        if (!PLUGIN_PRESENT) {
            log.info("[BRIDGE] EliteMobs not detected — BossBar replacement disabled.");
        }
    }

    private EliteMobsHook() {}

    /** True if EliteMobs is installed and its API has not failed yet. */
    public static boolean isAvailable() {
        return PLUGIN_PRESENT && !apiBroken;
    }

    /**
     * Returns the EliteEntity wrapper for a LivingEntity, or null if the entity
     * is not an EliteMobs-tracked elite, or if EliteMobs is unavailable.
     */
    public static EliteEntity getEliteEntity(LivingEntity entity) {
        if (!isAvailable() || entity == null) return null;
        try {
            return EntityTracker.getEliteMobEntity(entity);
        } catch (Throwable t) {
            markApiBroken(t);
            return null;
        }
    }

    /**
     * Returns the styled name (e.g. "Tier 5 Elder Alphawolf") for an EliteMobs
     * boss, or null if the entity is not EM-tracked or EM is unavailable.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code eliteEntity.getName()} — EliteMobs' canonical styled name from the
     *       YAML config. EM 10.3.1 (verified 2026-05-24) returns the proper styled name
     *       for all CustomBoss types via this API.</li>
     *   <li>{@code livingEntity.getCustomName()} — fallback for non-EM-tracked entities
     *       (vanilla / non-EM-named).</li>
     * </ol>
     *
     * <p><b>History:</b> Phase 7.1a (2026-05-03, EM 10.2.0) had customName as primary
     * after observing EVOKER-based CustomBosses returning "Evoker | 2" from eliteEntity.getName().
     * Reversed 2026-05-24 (EM 10.3.1) because customName now returns the Vanilla "Evoker | 2"
     * format for the same EVOKER-based bosses, while eliteEntity.getName() returns the styled
     * YAML name. customName remains as fallback for non-EM-tracked entities.
     */
    public static String getStyledName(LivingEntity entity) {
        if (entity == null) return null;

        // Primary: EliteMobs canonical styled name
        if (isAvailable()) {
            EliteEntity elite = getEliteEntity(entity);
            if (elite != null) {
                try {
                    String name = elite.getName();
                    if (name != null && !name.isEmpty()) return name;
                } catch (Throwable t) {
                    markApiBroken(t);
                }
            }
        }

        // Fallback: LivingEntity customName (non-EM-tracked entities)
        String customName = entity.getCustomName();
        return (customName == null || customName.isEmpty()) ? null : customName;
    }

    // --- Phase 7.3: native player-status dialog reroute (EM internals via reflection) ---

    private static volatile boolean dialogReflectionInit = false;
    private static java.lang.reflect.Method getIndexChestMenuNameMethod;
    private static java.lang.reflect.Method showPlayerStatusDialogMethod;

    private static synchronized void initDialogReflection() {
        if (dialogReflectionInit) return;
        dialogReflectionInit = true;
        try {
            Class<?> cfg = Class.forName(
                    "com.magmaguy.elitemobs.config.menus.premade.PlayerStatusMenuConfig");
            getIndexChestMenuNameMethod = cfg.getMethod("getIndexChestMenuName");
            Class<?> dlg = Class.forName(
                    "com.magmaguy.elitemobs.playerdata.statusscreen.PlayerStatusScreenDialog");
            showPlayerStatusDialogMethod = dlg.getMethod("showPlayerStatusDialog", Player.class);
        } catch (Throwable t) {
            getIndexChestMenuNameMethod = null;
            showPlayerStatusDialogMethod = null;
            log.warning("[BRIDGE] Phase 7.3 dialog reflection unavailable — reroute inert. Cause: " + t);
        }
    }

    /**
     * The configured title of EM's {@code /em} status index chest menu
     * (e.g. {@code §2EliteMobs Index}), or null if EM is unavailable / API changed.
     */
    public static String statusIndexMenuTitle() {
        if (!isAvailable()) return null;
        initDialogReflection();
        if (getIndexChestMenuNameMethod == null) return null;
        try {
            Object v = getIndexChestMenuNameMethod.invoke(null);
            return v instanceof String s ? s : null;
        } catch (Throwable t) {
            // Phase 7.3 targets EM internals — isolate the failure here instead of
            // markApiBroken() so a dialog-internal change does NOT disable the BossBar/
            // item hooks (Phase 7.1/7.2), which use EM's stable public API.
            log.warning("[BRIDGE] Phase 7.3 statusIndexMenuTitle failed (reroute inert this call): " + t);
            return null;
        }
    }

    /**
     * Trigger EM's native player-status MC dialog for the player (Geyser renders it
     * as a Bedrock form). Returns true on success, false if EM is unavailable / the
     * call failed.
     */
    public static boolean openNativeStatusDialog(Player player) {
        if (!isAvailable() || player == null) return false;
        initDialogReflection();
        if (showPlayerStatusDialogMethod == null) return false;
        try {
            showPlayerStatusDialogMethod.invoke(null, player);
            return true;
        } catch (Throwable t) {
            // Isolate Phase 7.3 failure (see statusIndexMenuTitle) — do not markApiBroken().
            log.warning("[BRIDGE] Phase 7.3 openNativeStatusDialog failed for "
                    + player.getName() + " (Bedrock player may see no menu): " + t);
            return false;
        }
    }

    // --- Phase 7.3b: native NPC quest dialog reroute (EM internals via reflection) ---

    private static volatile boolean questReflectionInit = false;
    private static java.lang.reflect.Field questDirectoriesField; // static Map<Inventory, QuestDirectory>
    private static java.lang.reflect.Field questInventoriesField; // static Map<Inventory, QuestInventory>
    private static java.lang.reflect.Field directoryQuestMapField; // QuestDirectory.questMap : Map<Integer,Quest>
    private static java.lang.reflect.Field directoryNpcField;      // QuestDirectory.npcEntity
    private static java.lang.reflect.Field inventoryQuestField;    // QuestInventory.quest
    private static java.lang.reflect.Field inventoryNpcField;      // QuestInventory.npcEntity
    private static java.lang.reflect.Method generateDialogMenuMethod;

    private static synchronized void initQuestReflection() {
        if (questReflectionInit) return;
        questReflectionInit = true;
        try {
            Class<?> menu = Class.forName("com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu");
            questDirectoriesField = menu.getDeclaredField("questDirectories");
            questDirectoriesField.setAccessible(true);
            questInventoriesField = menu.getDeclaredField("questInventories");
            questInventoriesField.setAccessible(true);

            Class<?> dir = Class.forName(
                    "com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu$QuestDirectory");
            directoryQuestMapField = dir.getDeclaredField("questMap");
            directoryQuestMapField.setAccessible(true);
            directoryNpcField = dir.getDeclaredField("npcEntity");
            directoryNpcField.setAccessible(true);

            Class<?> inv = Class.forName(
                    "com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu$QuestInventory");
            inventoryQuestField = inv.getDeclaredField("quest");
            inventoryQuestField.setAccessible(true);
            inventoryNpcField = inv.getDeclaredField("npcEntity");
            inventoryNpcField.setAccessible(true);

            Class<?> questMenu = Class.forName("com.magmaguy.elitemobs.quests.menus.QuestMenu");
            Class<?> npcClass = Class.forName("com.magmaguy.elitemobs.npcs.NPCEntity");
            generateDialogMenuMethod = questMenu.getMethod(
                    "generateDialogMenu", java.util.List.class, Player.class, npcClass);
        } catch (Throwable t) {
            // Null out ALL handles so a partial init failure cannot leave some non-null and
            // slip past the questDirectoriesField/questInventoriesField guard in tryRecoverQuestMenu.
            questDirectoriesField = null;
            questInventoriesField = null;
            directoryQuestMapField = null;
            directoryNpcField = null;
            inventoryQuestField = null;
            inventoryNpcField = null;
            generateDialogMenuMethod = null;
            log.warning("[BRIDGE] Phase 7.3b quest reflection unavailable — quest reroute inert. Cause: " + t);
        }
    }

    /**
     * If {@code inventory} is an EM NPC quest chest (single quest or multi-quest directory),
     * recover the quests + originating NPC and <b>remove</b> the inventory from EM's internal
     * tracking map, returning the context. Otherwise returns empty.
     *
     * <p>The map entry is removed because the caller cancels the chest's open — EM only cleans
     * these maps on {@code InventoryCloseEvent}, which never fires for a cancelled open, so
     * without removal the entry would leak.
     */
    public static java.util.Optional<QuestMenuContext> tryRecoverQuestMenu(
            org.bukkit.inventory.Inventory inventory) {
        if (!isAvailable() || inventory == null) return java.util.Optional.empty();
        initQuestReflection();
        if (questDirectoriesField == null || questInventoriesField == null) {
            return java.util.Optional.empty();
        }
        try {
            // Multi-quest directory
            java.util.Map<?, ?> directories = (java.util.Map<?, ?>) questDirectoriesField.get(null);
            Object directory = directories == null ? null : directories.get(inventory);
            if (directory != null) {
                java.util.Map<?, ?> questMap = (java.util.Map<?, ?>) directoryQuestMapField.get(directory);
                java.util.List<Object> quests = new java.util.ArrayList<>(
                        questMap == null ? java.util.List.of() : questMap.values());
                Object npc = directoryNpcField.get(directory);
                directories.remove(inventory);
                return java.util.Optional.of(new QuestMenuContext(quests, npc));
            }
            // Single-quest entry
            java.util.Map<?, ?> inventories = (java.util.Map<?, ?>) questInventoriesField.get(null);
            Object questInv = inventories == null ? null : inventories.get(inventory);
            if (questInv != null) {
                Object quest = inventoryQuestField.get(questInv);
                java.util.List<Object> quests = new java.util.ArrayList<>();
                if (quest != null) quests.add(quest);
                Object npc = inventoryNpcField.get(questInv);
                inventories.remove(inventory);
                return java.util.Optional.of(new QuestMenuContext(quests, npc));
            }
            return java.util.Optional.empty();
        } catch (Throwable t) {
            log.warning("[BRIDGE] Phase 7.3b tryRecoverQuestMenu failed (quest reroute inert this call): " + t);
            return java.util.Optional.empty();
        }
    }

    /**
     * Trigger EM's native NPC quest MC dialog for the player (Geyser renders it as a Bedrock
     * form). {@code quests} and {@code npcEntity} must be the objects from
     * {@link #tryRecoverQuestMenu}. Returns true on success, false if EM is unavailable / the
     * call failed.
     */
    public static boolean openNativeQuestDialog(Player player, java.util.List<?> quests, Object npcEntity) {
        if (!isAvailable() || player == null) return false;
        initQuestReflection();
        if (generateDialogMenuMethod == null) return false;
        try {
            generateDialogMenuMethod.invoke(null, quests, player, npcEntity);
            return true;
        } catch (Throwable t) {
            log.warning("[BRIDGE] Phase 7.3b openNativeQuestDialog failed for "
                    + player.getName() + " (Bedrock player may see no menu): " + t);
            return false;
        }
    }

    private static void markApiBroken(Throwable t) {
        if (apiBroken) return;
        apiBroken = true;
        log.warning("[BRIDGE] EliteMobs API call failed — disabling BossBar replacement. Cause: " + t);
    }
}
