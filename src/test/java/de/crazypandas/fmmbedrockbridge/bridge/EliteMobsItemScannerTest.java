package de.crazypandas.fmmbedrockbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link EliteMobsItemScanner#pickGearTextureRef}.
 *
 * Bug context: the gear scanner originally hard-coded the texture key "0", so EM
 * gear models that Blockbench assigned a different numeric key (e.g. "29", "1")
 * were silently skipped — ultimatium_sword and corrupted_trident never reached
 * the Bedrock pack.
 */
class EliteMobsItemScannerTest {

    private static final Gson GSON = new Gson();

    private JsonObject textures(String json) {
        return GSON.fromJson(json, JsonObject.class);
    }

    @Test
    void picksKeyZeroWhenPresent() {
        JsonObject t = textures("""
                {"0": "elitemobs:items/bronzesword", "particle": "elitemobs:items/bronzesword"}""");
        assertEquals("elitemobs:items/bronzesword", EliteMobsItemScanner.pickGearTextureRef(t));
    }

    @Test
    void picksNonZeroNumericKey() {
        // ultimatium_sword.json uses key "29" — previously skipped
        JsonObject t = textures("""
                {"29": "elitemobs:items/ultimatiumsword", "particle": "elitemobs:items/ultimatiumsword"}""");
        assertEquals("elitemobs:items/ultimatiumsword", EliteMobsItemScanner.pickGearTextureRef(t));
    }

    @Test
    void picksSoleKeyWithoutParticle() {
        // corrupted_trident.json has only key "1", no particle entry
        JsonObject t = textures("""
                {"1": "elitemobs:items/corruptedtrident2"}""");
        assertEquals("elitemobs:items/corruptedtrident2", EliteMobsItemScanner.pickGearTextureRef(t));
    }

    @Test
    void returnsNullWhenOnlyParticle() {
        JsonObject t = textures("""
                {"particle": "elitemobs:items/whatever"}""");
        assertNull(EliteMobsItemScanner.pickGearTextureRef(t));
    }

    @Test
    void returnsNullWhenEmpty() {
        assertNull(EliteMobsItemScanner.pickGearTextureRef(textures("{}")));
    }
}
