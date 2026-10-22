package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static de.crazypandas.fmmbedrockbridge.bridge.RerouteDecision.Action;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerouteDecisionTest {

    @Test
    void statusMatchWinsWhenEnabled() {
        assertEquals(Action.STATUS, RerouteDecision.resolve(true, true, true, true));
        assertEquals(Action.STATUS, RerouteDecision.resolve(true, true, false, false));
    }

    @Test
    void questMatchUsedWhenNoStatusMatch() {
        assertEquals(Action.QUEST, RerouteDecision.resolve(true, false, true, true));
        assertEquals(Action.QUEST, RerouteDecision.resolve(false, false, true, true));
    }

    @Test
    void statusDisabledFallsThroughToQuest() {
        // status flag off but title would match → status is skipped, quest taken
        assertEquals(Action.QUEST, RerouteDecision.resolve(false, true, true, true));
    }

    @Test
    void questDisabledIsSkippedEvenIfMatched() {
        assertEquals(Action.NONE, RerouteDecision.resolve(true, false, false, true));
    }

    @Test
    void noMatchOrBothDisabledIsNone() {
        assertEquals(Action.NONE, RerouteDecision.resolve(true, false, true, false));
        assertEquals(Action.NONE, RerouteDecision.resolve(false, true, false, true));
    }

    // --- Phase 7.3c: class menu ------------------------------------------------------------------
    // EliteMobs' MenuPresentation.supportsDialogs is
    //   useBookMenus && !isBedrock && !onlyUseBedrockMenus && mc >= 1.21.6
    // The class menu gets its dialog when Bedrock is the ONLY reason it would be a chest.

    @Test
    void bedrockPlayerGetsTheClassDialog() {
        assertTrue(RerouteDecision.classMenuUsesDialog(true, true, true, false, true));
    }

    @Test
    void javaPlayersAreNeverRerouted() {
        // They already get what EliteMobs decided for them — dialog or their chosen chest.
        assertFalse(RerouteDecision.classMenuUsesDialog(true, false, true, false, true));
    }

    @Test
    void aPlayerWhoChoseChestMenusKeepsThem() {
        assertFalse(RerouteDecision.classMenuUsesDialog(true, true, false, false, true));
    }

    @Test
    void theServerWideChestSettingIsRespected() {
        assertFalse(RerouteDecision.classMenuUsesDialog(true, true, true, true, true));
    }

    @Test
    void noDialogsBeforeMinecraft1216() {
        assertFalse(RerouteDecision.classMenuUsesDialog(true, true, true, false, false));
    }

    @Test
    void classRerouteSwitchedOff() {
        assertFalse(RerouteDecision.classMenuUsesDialog(false, true, true, false, true));
    }

    // Phase 7.3b — since EliteMobs 10.9.8 the quest chest is opened BEFORE it is registered in
    // EM's quest maps, so the open event can only be recognised by who is opening it.
    private static final String QUEST_MENU = "com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu";

    @Test
    void anOpenFromEliteMobsQuestMenuIsRecognised() {
        assertTrue(RerouteDecision.calledFrom(java.util.stream.Stream.of(
                "de.crazypandas.fmmbedrockbridge.bridge.BedrockMenuRerouteListener",
                "org.bukkit.craftbukkit.entity.CraftHumanEntity",
                QUEST_MENU,
                "com.magmaguy.elitemobs.npcs.NPCInteractions"), QUEST_MENU));
    }

    @Test
    void itsNestedClassesCountToo() {
        assertTrue(RerouteDecision.calledFrom(java.util.stream.Stream.of(QUEST_MENU + "$QuestDirectory"), QUEST_MENU));
    }

    @Test
    void otherMenusAreNotQuestMenus() {
        assertFalse(RerouteDecision.calledFrom(java.util.stream.Stream.of(
                "com.magmaguy.elitemobs.menus.SellMenu",
                QUEST_MENU + "Extra"), QUEST_MENU));
    }
}
