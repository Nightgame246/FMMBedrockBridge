package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("ab", BedrockGlyphFilter.strip("ab"), "U+E000 is the first PUA char");
        assertEquals("ab", BedrockGlyphFilter.strip("ab"), "U+F8FF is the last PUA char");
        assertEquals("a\uDFFFb", BedrockGlyphFilter.strip("a\uDFFFb"), "just below must survive");
    }

    @Test
    void recognisesWhetherStrippingIsNeededAtAll() {
        // The interceptor asks this first so untouched packets are never rebuilt.
        assertTrue(BedrockGlyphFilter.containsGlyphs("HP "));
        assertFalse(BedrockGlyphFilter.containsGlyphs("HP 332/332"));
        assertFalse(BedrockGlyphFilter.containsGlyphs(""));
        assertFalse(BedrockGlyphFilter.containsGlyphs(null));
    }
}
