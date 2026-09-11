package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7.5 — EliteMobs draws its combat HUD from a Java resource-pack font whose glyphs sit in
 * the Unicode Private Use Area. Bedrock has no such font and maps those same codepoints onto its
 * own symbol sheet, so every bar segment turns into a full item icon.
 *
 * <p>Measured on EliteMobs 10.9.0: {@code combat_hud_concept_16.json} defines 703 glyphs, 689 of
 * them in U+E000–U+F8FF. In-game this produced several hundred armour and carrot icons filling
 * half the screen, re-rendered on the compositor's keepalive loop — enough to lag the client out.
 */
class BedrockGlyphFilterTest {

    @Test
    void stripsPrivateUseAreaGlyphs() {
        String hud = "Adventurer  332/332";

        assertEquals("Adventurer 332/332", BedrockGlyphFilter.strip(hud));
    }

    @Test
    void keepsOrdinaryTextUntouched() {
        // Java players and plain messages must pass through byte-identical.
        String plain = "§aAdventurer §7332/332 §6100/100";

        assertEquals(plain, BedrockGlyphFilter.strip(plain));
    }

    @Test
    void keepsUmlautsAndSymbolsThatBedrockCanRender() {
        String text = "§7Fähigkeit bereit — 100% · ▶ Schattenschritt";

        assertEquals(text, BedrockGlyphFilter.strip(text));
    }

    @Test
    void collapsesTheRunsOfGlyphsRatherThanLeavingRaggedSpaces() {
        // A bar is hundreds of consecutive glyphs; removing them must not leave a gap storm.
        StringBuilder bar = new StringBuilder("HP ");
        for (int i = 0; i < 300; i++) bar.append('');
        bar.append(" 332/332");

        assertEquals("HP 332/332", BedrockGlyphFilter.strip(bar.toString()));
    }

    @Test
    void aStringThatIsOnlyGlyphsBecomesEmpty() {
        assertEquals("", BedrockGlyphFilter.strip(""));
    }

    @Test
    void handlesNullAndEmptyWithoutThrowing() {
        assertEquals(null, BedrockGlyphFilter.strip(null));
        assertEquals("", BedrockGlyphFilter.strip(""));
    }

    @Test
    void coversTheWholePrivateUseAreaIncludingItsEdges() {
        // A stripped glyph leaves a separator behind — see the separator test below for why.
        assertEquals("a b", BedrockGlyphFilter.strip("ab"), "U+E000 is the first PUA char");
        assertEquals("a b", BedrockGlyphFilter.strip("ab"), "U+F8FF is the last PUA char");
        assertEquals("a\uDFFFb", BedrockGlyphFilter.strip("a\uDFFFb"), "just below must survive");
    }

    @Test
    void aGlyphRunBetweenTwoValuesBecomesASeparator() {
        // Seen live on 11.09.2026: the HUD delivers "56/60", a bar, then "92/100" — with no
        // spaces of its own. Dropping the bar outright produced "56/6092/100", which is
        // unreadable. One run therefore collapses to exactly one space.
        assertEquals("56/60 92/100",
                BedrockGlyphFilter.strip("56/6092/100"));
    }

    @Test
    void aGlyphRunAtEitherEndLeavesNoDanglingSpace() {
        assertEquals("Adventurer", BedrockGlyphFilter.strip("Adventurer"));
        assertEquals("Adventurer", BedrockGlyphFilter.strip("Adventurer"));
    }

    @Test
    void recognisesWhetherStrippingIsNeededAtAll() {
        // The interceptor asks this first so untouched packets are never rebuilt.
        assertTrue(BedrockGlyphFilter.containsGlyphs("HP "));
        assertFalse(BedrockGlyphFilter.containsGlyphs("HP 332/332"));
        assertFalse(BedrockGlyphFilter.containsGlyphs(""));
        assertFalse(BedrockGlyphFilter.containsGlyphs(null));
    }

    @Test
    void findsTheMessageAfterTheLastGlyph() {
        String raw = "\uE010\uE011Adventurer\uE012 60/60\uE013 12/100\uE014 §7Dodge! -20 Stamina";

        assertEquals("§7Dodge! -20 Stamina", BedrockGlyphFilter.messageAfterGlyphs(raw));
    }

    @Test
    void aMessageMayCarryItsOwnValuePair() {
        // THE bug this replaced: cutting at the last "n/m" pair chopped inside the message and
        // left only "required)" on the bar.
        String raw = "\uE010Adventurer\uE011 60/60\uE012 0/100\uE013 Not enough Stamina (20/100 required)";

        assertEquals("Not enough Stamina (20/100 required)",
                BedrockGlyphFilter.messageAfterGlyphs(raw));
    }

    @Test
    void aHudWithoutAMessageYieldsNothing() {
        assertNull(BedrockGlyphFilter.messageAfterGlyphs("\uE010Adventurer\uE011 60/60\uE012"));
    }

    @Test
    void textWithoutGlyphsHasNothingToSplit() {
        assertNull(BedrockGlyphFilter.messageAfterGlyphs("plain text, no glyphs"));
        assertNull(BedrockGlyphFilter.messageAfterGlyphs(null));
        assertNull(BedrockGlyphFilter.messageAfterGlyphs(""));
    }

    @Test
    void aTrailingSeparatorIsNotAMessage() {
        assertNull(BedrockGlyphFilter.messageAfterGlyphs("\uE010Adventurer\uE011 60/60\uE012 ·"));
    }

    @Test
    void dropsTheClassNameEliteMobsRepeatsInFrontOfItsMessages() {
        // Live line on 11.09.2026: "Adventurer Lv1 ♥60/60 38/100 Adventurer [EM] Teleportiere…"
        // — the name appeared twice, once from our HUD and once from EliteMobs' own prefix.
        assertEquals("§7[EM] Teleportiere in §a1 §7Sekunden...",
                BedrockGlyphFilter.withoutLeadingName(
                        "§fAdventurer§f §7[EM] Teleportiere in §a1 §7Sekunden...", "Adventurer"));
    }

    @Test
    void leavesAMessageAloneWhenItDoesNotStartWithTheName() {
        assertEquals("Not enough Stamina (20 required).",
                BedrockGlyphFilter.withoutLeadingName("Not enough Stamina (20 required).", "Adventurer"));
    }

    @Test
    void aMessageThatIsOnlyTheNameBecomesNothing() {
        assertNull(BedrockGlyphFilter.withoutLeadingName("§fAdventurer", "Adventurer"));
    }

    @Test
    void withoutLeadingNameSurvivesNulls() {
        assertNull(BedrockGlyphFilter.withoutLeadingName(null, "Adventurer"));
        assertEquals("text", BedrockGlyphFilter.withoutLeadingName("text", null));
        assertEquals("text", BedrockGlyphFilter.withoutLeadingName("text", ""));
    }
}
