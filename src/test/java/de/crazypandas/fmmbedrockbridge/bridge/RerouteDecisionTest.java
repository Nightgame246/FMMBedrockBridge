package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static de.crazypandas.fmmbedrockbridge.bridge.RerouteDecision.Action;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
