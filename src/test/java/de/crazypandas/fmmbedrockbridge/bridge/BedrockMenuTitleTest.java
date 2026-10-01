package de.crazypandas.fmmbedrockbridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 7.6. The cases live in a JSON file that the Python generator's tests read too, so the
 * bridge and the pack provably agree on which marker belongs to which EliteMobs menu.
 */
class BedrockMenuTitleTest {

    private static JsonObject cases() {
        var in = BedrockMenuTitleTest.class.getResourceAsStream("/bedrock-menus/cases.json");
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @TestFactory
    List<DynamicTest> markerMapping() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement e : cases().getAsJsonArray("markers")) {
            int em = Integer.parseInt(e.getAsJsonObject().get("em").getAsString(), 16);
            int marker = Integer.parseInt(e.getAsJsonObject().get("marker").getAsString(), 16);
            tests.add(DynamicTest.dynamicTest(Integer.toHexString(em),
                    () -> assertEquals(marker, BedrockMenuTitle.markerFor(em))));
        }
        return tests;
    }

    @Test
    void spacingCharactersNeverGetAMarker() {
        JsonArray spacing = cases().getAsJsonArray("spacing");
        for (JsonElement e : spacing) {
            int cp = Integer.parseInt(e.getAsString(), 16);
            assertThrows(IllegalArgumentException.class, () -> BedrockMenuTitle.markerFor(cp));
        }
    }

    @Test
    void outsideTheBlockHasNoMarker() {
        assertThrows(IllegalArgumentException.class, () -> BedrockMenuTitle.markerFor(0xF0DFF));
        assertThrows(IllegalArgumentException.class, () -> BedrockMenuTitle.markerFor(0xF0F0C));
    }

    private static java.util.Set<Integer> known() {
        java.util.Set<Integer> known = new java.util.HashSet<>();
        for (JsonElement e : cases().getAsJsonArray("known")) known.add(Integer.parseInt(e.getAsString(), 16));
        return known;
    }

    @Test
    void knownBackgroundsAreLoadedFromTheGeneratedResource() {
        // Written by tools/bedrock-menus/generate.py; the bridge only marks what the pack can show.
        org.junit.jupiter.api.Assertions.assertFalse(BedrockMenuTitle.KNOWN_BACKGROUNDS.isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(BedrockMenuTitle.KNOWN_BACKGROUNDS.contains(0xF0E01));
    }

    @TestFactory
    List<DynamicTest> titleRewrites() {
        java.util.Set<Integer> known = known();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement e : cases().getAsJsonArray("titles")) {
            JsonObject c = e.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(c.get("name").getAsString(), () -> assertEquals(
                    c.get("expected").getAsString(),
                    BedrockMenuTitle.rewrite(c.get("input").getAsString(), c.get("hideSlots").getAsBoolean(), known))));
        }
        return tests;
    }

    @Test
    void untouchedTitleIsTheSameInstance() {
        // The interceptor re-encodes only when the title changed; identity makes that check exact.
        String title = "§6Shop";
        assertSame(title, BedrockMenuTitle.rewrite(title, true));
    }
}
