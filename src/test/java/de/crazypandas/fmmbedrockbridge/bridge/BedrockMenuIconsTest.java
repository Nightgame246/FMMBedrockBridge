package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 7.7 — which EliteMobs menu icons get paper as base item for Bedrock. */
class BedrockMenuIconsTest {

    private static final List<String> PREFIXES = List.of("elitemobs:ui/", "nightbreak:ui/");
    private static final Set<String> EXCLUDES = Set.of("elitemobs:ui/redcrown", "elitemobs:ui/yellowcrown");

    private static boolean rebase(String base, String model) {
        return BedrockMenuIcons.shouldRebase(base, model, PREFIXES, EXCLUDES);
    }

    @Test
    void emIconOnEmerald_true() {
        assertTrue(rebase("minecraft:emerald", "elitemobs:ui/anvilhammer"));
    }

    @Test
    void emIconOnBanner_true() {
        assertTrue(rebase("minecraft:green_banner", "elitemobs:ui/boxinput"));
    }

    @Test
    void emIconOnRedstone_true() {
        assertTrue(rebase("minecraft:redstone", "elitemobs:ui/handwithcoins"));
    }

    @Test
    void nightbreakCrossOnBarrier_true() {
        assertTrue(rebase("minecraft:barrier", "nightbreak:ui/redcross"));
    }

    @Test
    void alreadyPaper_false() {
        assertFalse(rebase("minecraft:paper", "elitemobs:ui/goldenquestionmark"));
    }

    @Test
    void excludedCrown_false() {
        assertFalse(rebase("minecraft:golden_helmet", "elitemobs:ui/redcrown"));
    }

    @Test
    void noItemModel_false() {
        assertFalse(rebase("minecraft:emerald", null));
    }

    @Test
    void vanillaDefaultModel_false() {
        assertFalse(rebase("minecraft:emerald", "minecraft:emerald"));
    }

    @Test
    void gearNamespace_false() {
        assertFalse(rebase("minecraft:iron_sword", "elitemobs:equipment/bronze_sword"));
        assertFalse(rebase("minecraft:leather_horse_armor", "freeminecraftmodels:some/model"));
    }

    @Test
    void caseInsensitive() {
        assertTrue(BedrockMenuIcons.shouldRebase("minecraft:emerald", "elitemobs:ui/anvilhammer",
                List.of("EliteMobs:UI/"), Set.of()));
        assertFalse(BedrockMenuIcons.shouldRebase("minecraft:golden_helmet", "elitemobs:ui/redcrown",
                PREFIXES, Set.of("ELITEMOBS:UI/REDCROWN")));
        assertFalse(BedrockMenuIcons.shouldRebase("MINECRAFT:PAPER", "elitemobs:ui/update",
                PREFIXES, EXCLUDES));
    }

    @Test
    void nullBase_false() {
        assertFalse(rebase(null, "elitemobs:ui/anvilhammer"));
    }
}
