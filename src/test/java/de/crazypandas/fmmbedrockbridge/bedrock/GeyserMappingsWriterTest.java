package de.crazypandas.fmmbedrockbridge.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeyserMappingsWriterTest {

    @Test
    void writesFormatVersion2WithItems(@TempDir Path tmp) throws Exception {
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 31173, "/x/a.png", "em_bagofcoins");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 31174, "/x/b.png", "em_anvilhammer");
        EMCustomItem c = new EMCustomItem("minecraft:paper", 99, "/x/c.png", "em_scroll");

        Path out = tmp.resolve("em_bridge_mappings.json");
        GeyserMappingsWriter.write(List.of(a, b, c), out);

        assertTrue(Files.exists(out));
        JsonObject root = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
        assertEquals(2, root.get("format_version").getAsInt());

        JsonObject items = root.getAsJsonObject("items");
        JsonArray emerald = items.getAsJsonArray("minecraft:emerald");
        assertEquals(2, emerald.size());

        JsonObject first = emerald.get(0).getAsJsonObject();
        assertEquals("definition", first.get("type").getAsString());
        assertEquals("bridge_em:em_bagofcoins", first.get("bedrock_identifier").getAsString());
        assertEquals("bridge_em:em_bagofcoins", first.get("model").getAsString());
        assertEquals("em_bagofcoins",
                first.getAsJsonObject("bedrock_options").get("icon").getAsString());

        // No predicate — Geyser custom-item-v2 routes via (base material + model field).
        // Each CMD gets a distinct bedrock_identifier/model so per-CMD distinction comes
        // from the unique identifier, not from a predicate.
        org.junit.jupiter.api.Assertions.assertFalse(first.has("predicate"),
                "Definition must NOT contain predicate (invalid in Geyser custom-item-v2 schema)");
    }

    @Test
    void deduplicatesSameMaterialAndCmd(@TempDir Path tmp) throws Exception {
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, "/x/a.png", "em_x");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 1, "/x/a.png", "em_x");
        Path out = tmp.resolve("m.json");
        GeyserMappingsWriter.write(List.of(a, b), out);
        JsonObject root = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
        assertEquals(1, root.getAsJsonObject("items").getAsJsonArray("minecraft:emerald").size());
    }
}
