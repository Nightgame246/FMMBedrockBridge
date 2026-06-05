package de.crazypandas.fmmbedrockbridge.elite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class QuestMenuContextTest {

    @Test
    void carriesQuestsAndNpcWithoutInspectingThem() {
        Object npc = new Object();
        List<?> quests = List.of("quest-a", "quest-b");

        QuestMenuContext ctx = new QuestMenuContext(quests, npc);

        assertEquals(quests, ctx.quests());
        assertSame(npc, ctx.npcEntity());
    }
}
