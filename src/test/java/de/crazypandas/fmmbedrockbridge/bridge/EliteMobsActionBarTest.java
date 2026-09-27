package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7.4 — the bridge publishes ability feedback through EliteMobs' compositor instead of
 * writing to the client itself.
 *
 * <p>Two kinds of test live here. The first checks our own resolution by name. The rest are
 * <b>upstream contract</b> tests: they assert the properties of {@code Source.ABILITY_INPUT}
 * that make it the right channel. If MagmaGuy ever reorders those priorities or shortens the
 * lifetime, feedback would silently lose against the permanent class HUD again — exactly the
 * bug this phase fixes — and these tests are what catches it at build time instead of in-game.
 */
class EliteMobsActionBarTest {

    private static final String SOURCE_CLASS =
            "com.magmaguy.elitemobs.presentation.actionbar.ActionBarCompositor$Source";

    @Test
    void resolvesTheCompositorFromTheClasspath() {
        assertTrue(EliteMobsActionBar.isAvailable(),
                "EliteMobs 10.9.0 carries ActionBarCompositor.show(Player, Source, String)"
                        + " — verified with javap on the 10.9.0 artifact");
        assertEquals("", EliteMobsActionBar.unavailableReason(),
                "once resolution succeeds there is nothing left to explain");
    }

    @Test
    void abilityInputOutranksThePermanentClassHud() throws Exception {
        assertTrue(priorityOf("ABILITY_INPUT") > priorityOf("CLASS_HUD"),
                "feedback must win against the class HUD, otherwise the HUD's every-tick"
                        + " re-render swallows it — measured as 310 vs 100 in 10.9.0");
    }

    @Test
    void abilityInputExpiresInsteadOfStickingAround() throws Exception {
        Object source = constant("ABILITY_INPUT");
        boolean persistent = (boolean) source.getClass()
                .getMethod("isPersistent").invoke(source);
        assertTrue(!persistent,
                "ability feedback has to expire so the class HUD comes back on its own");

        long ticks = (long) source.getClass()
                .getMethod("defaultDurationTicks").invoke(source);
        assertNotEquals(-1L, ticks, "a persistent source would never release the bar");
        assertTrue(ticks > 0 && ticks <= 100,
                "expected a short readable lifetime, got " + ticks + " ticks");
    }

    private static int priorityOf(String constantName) throws Exception {
        Object source = constant(constantName);
        return (int) source.getClass().getMethod("priority").invoke(source);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(String constantName) throws Exception {
        Class<?> sourceClass = Class.forName(SOURCE_CLASS);
        return Enum.valueOf((Class<Enum>) sourceClass, constantName);
    }
}
