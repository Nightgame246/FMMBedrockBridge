package de.crazypandas.fmmbedrockbridge.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockItemPackBuilderTest {

    @Test
    void writesValidMcpackZip(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("source.png");
        Files.write(png, new byte[]{(byte)0x89, 'P', 'N', 'G'});
        EMCustomItem item = new EMCustomItem("minecraft:emerald", 31173, png.toString(), "em_bagofcoins");

        Path outZip = tmp.resolve("em_bridge_pack.mcpack");
        BedrockItemPackBuilder.build(List.of(item), "abc123def4", outZip);

        assertTrue(Files.exists(outZip));
        Map<String, byte[]> contents = readZip(outZip);

        assertTrue(contents.containsKey("manifest.json"), "Must contain manifest.json");
        assertTrue(contents.containsKey("pack_icon.png"), "Must contain pack_icon.png");
        assertTrue(contents.containsKey("textures/items/em_bagofcoins.png"), "Must contain item PNG");
        assertTrue(contents.containsKey("textures/item_texture.json"), "Must contain item_texture.json");
    }

    @Test
    void manifestHasResourcesModuleAndStableUuidsFromHash(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("source.png");
        Files.write(png, new byte[]{(byte)0x89, 'P', 'N', 'G'});
        EMCustomItem item = new EMCustomItem("minecraft:emerald", 1, png.toString(), "em_a");

        Path outZip = tmp.resolve("em_bridge_pack.mcpack");
        BedrockItemPackBuilder.build(List.of(item), "deadbeef12", outZip);
        Path outZip2 = tmp.resolve("em_bridge_pack2.mcpack");
        BedrockItemPackBuilder.build(List.of(item), "deadbeef12", outZip2);

        String mf1 = new String(readZip(outZip).get("manifest.json"), StandardCharsets.UTF_8);
        String mf2 = new String(readZip(outZip2).get("manifest.json"), StandardCharsets.UTF_8);
        JsonObject m1 = JsonParser.parseString(mf1).getAsJsonObject();
        JsonObject m2 = JsonParser.parseString(mf2).getAsJsonObject();

        String headerUuid1 = m1.getAsJsonObject("header").get("uuid").getAsString();
        String headerUuid2 = m2.getAsJsonObject("header").get("uuid").getAsString();
        assertEquals(headerUuid1, headerUuid2, "Same input hash → same header UUID");

        assertTrue(m1.getAsJsonArray("modules").size() >= 1);
        assertEquals("resources", m1.getAsJsonArray("modules").get(0).getAsJsonObject()
                .get("type").getAsString());
    }

    @Test
    void itemTextureJsonMapsKeysCorrectly(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("a.png");
        Files.write(png, new byte[]{1});
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, png.toString(), "em_bagofcoins");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 2, png.toString(), "em_anvilhammer");

        Path outZip = tmp.resolve("p.mcpack");
        BedrockItemPackBuilder.build(List.of(a, b), "h", outZip);

        String json = new String(readZip(outZip).get("textures/item_texture.json"), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("fmmbridge_em", root.get("resource_pack_name").getAsString());
        JsonObject data = root.getAsJsonObject("texture_data");
        assertEquals("textures/items/em_bagofcoins",
                data.getAsJsonObject("em_bagofcoins").get("textures").getAsString());
        assertEquals("textures/items/em_anvilhammer",
                data.getAsJsonObject("em_anvilhammer").get("textures").getAsString());
    }

    private Map<String, byte[]> readZip(Path zipPath) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream in = zf.getInputStream(e); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    in.transferTo(bos);
                    out.put(e.getName(), bos.toByteArray());
                }
            }
        }
        return out;
    }
}
