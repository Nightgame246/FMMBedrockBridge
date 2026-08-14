package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.boss.BossBar;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Resolves the wire-level UUID of a Bukkit {@link BossBar} — the same UUID that shows up in
 * the BOSS_EVENT packet — so {@link PacketInterceptor} can recognise our own bars positively
 * instead of guessing.
 *
 * <p><b>Why this exists.</b> The Bukkit API deliberately hides the bar's UUID, which is why
 * Phase 7.1a originally identified our own packets by a first-match-wins ordering heuristic
 * ("the first title-matching ADD is ours, later ones are EliteMobs'"). That premise broke with
 * EliteMobs 10.8.0: {@code BossHealthBarManager} now keeps a pool of up to four reusable bars
 * per player and re-titles them for whichever boss currently occupies a slot, and
 * {@code BossBarOrderManager} re-sends bars (removePlayer + addPlayer) purely to enforce
 * ordering. Under that behaviour "first ADD wins" can hand an EliteMobs slot our identity and
 * suppress our own bar instead of theirs.
 *
 * <p><b>How.</b> CraftBukkit's {@code CraftBossBar} wraps an NMS {@code ServerBossEvent}, whose
 * superclass holds the packet UUID. Rather than hard-coding field names — which differ between
 * mappings and move between releases — we walk the wrapper's declared fields for the handle,
 * then scan the handle's class hierarchy for the single {@link UUID}-typed field.
 *
 * <p><b>Failure is expected and safe.</b> Any reflection problem returns {@code null}, and the
 * caller keeps the legacy heuristic. This must never throw into the packet path.
 */
final class BossBarUuidResolver {

    /** Logged once so a broken resolve doesn't spam the console on every boss spawn. */
    private static volatile boolean failureLogged = false;

    private BossBarUuidResolver() {
    }

    /**
     * @return the bar's wire UUID, or {@code null} if it cannot be determined on this server
     *         implementation — in which case the caller must fall back to the heuristic.
     */
    static UUID resolve(BossBar bossBar) {
        if (bossBar == null) return null;
        try {
            Object handle = findHandle(bossBar);
            if (handle == null) return null;
            return findUuid(handle);
        } catch (Throwable t) {
            logFailureOnce(t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * Finds the NMS boss event behind the Craft wrapper. Prefers a field literally named
     * {@code handle}; otherwise takes the first non-null field whose type sits outside the
     * Bukkit API, which is what the NMS handle always is.
     */
    private static Object findHandle(BossBar bossBar) throws IllegalAccessException {
        Object fallback = null;
        for (Class<?> c = bossBar.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!trySetAccessible(field)) continue;
                Object value = field.get(bossBar);
                if (value == null) continue;

                if ("handle".equals(field.getName())) return value;

                String typeName = field.getType().getName();
                if (!typeName.startsWith("org.bukkit.") && !typeName.startsWith("java.")
                        && !typeName.startsWith("net.kyori.") && fallback == null) {
                    fallback = value;
                }
            }
        }
        return fallback;
    }

    /** Scans the handle's class hierarchy for its UUID field (NMS {@code BossEvent#id}). */
    private static UUID findUuid(Object handle) throws IllegalAccessException {
        for (Class<?> c = handle.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() != UUID.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!trySetAccessible(field)) continue;
                Object value = field.get(handle);
                if (value instanceof UUID uuid) return uuid;
            }
        }
        logFailureOnce("no UUID field on " + handle.getClass().getName());
        return null;
    }

    private static boolean trySetAccessible(Field field) {
        try {
            field.setAccessible(true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void logFailureOnce(String detail) {
        if (failureLogged) return;
        failureLogged = true;
        FMMBedrockBridge.getInstance().getLogger().info(
                "[BRIDGE] Could not read BossBar UUIDs reflectively (" + detail + "). "
                        + "Phase 7.1a falls back to the legacy first-match heuristic, which is "
                        + "less reliable against EliteMobs' pooled boss bars.");
    }
}
