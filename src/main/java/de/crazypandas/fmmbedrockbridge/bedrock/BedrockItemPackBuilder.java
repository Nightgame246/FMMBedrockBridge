package de.crazypandas.fmmbedrockbridge.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BedrockItemPackBuilder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 16x16 fully transparent PNG used as pack_icon.png placeholder.
    private static final byte[] TRANSPARENT_PNG_16 = java.util.HexFormat.of().parseHex(
            "89504e470d0a1a0a0000000d49484452000000100000001008060000001ff3ff61"
          + "0000000d49444154789c63f8ffff3f0303000700026100087fcafe0000000049454e44ae426082"
    );

    private BedrockItemPackBuilder() {}

    public static void build(List<EMCustomItem> items, String packHashHex, Path outputMcpack)
            throws IOException {
        Files.createDirectories(outputMcpack.getParent());

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputMcpack))) {
            writeEntry(zip, "manifest.json", buildManifest(packHashHex).getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "pack_icon.png", TRANSPARENT_PNG_16);

            Set<String> writtenKeys = new HashSet<>();
            for (EMCustomItem item : items) {
                if (!writtenKeys.add(item.bedrockTextureKey())) continue;
                byte[] png = Files.exists(Path.of(item.sourceTexturePath()))
                        ? Files.readAllBytes(Path.of(item.sourceTexturePath()))
                        : TRANSPARENT_PNG_16;
                writeEntry(zip, "textures/items/" + item.bedrockTextureKey() + ".png", png);
            }

            writeEntry(zip, "textures/item_texture.json",
                    buildItemTextureJson(items, writtenKeys).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String buildManifest(String packHashHex) {
        UUID headerUuid = deterministicUuid("header:" + packHashHex);
        UUID moduleUuid = deterministicUuid("module:" + packHashHex);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);

        JsonArray version = new JsonArray();
        version.add(1);
        version.add(0);
        version.add((int) (System.currentTimeMillis() & 0xFFFF));

        JsonObject header = new JsonObject();
        header.addProperty("name", "FMMBedrockBridge EM Items");
        header.addProperty("description", "2D EliteMobs custom_model_data items for Bedrock clients");
        header.addProperty("uuid", headerUuid.toString());
        header.add("version", version);
        JsonArray minEngine = new JsonArray();
        minEngine.add(1);
        minEngine.add(20);
        minEngine.add(0);
        header.add("min_engine_version", minEngine);
        root.add("header", header);

        JsonArray modules = new JsonArray();
        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", moduleUuid.toString());
        module.add("version", version);
        modules.add(module);
        root.add("modules", modules);

        return GSON.toJson(root);
    }

    private static String buildItemTextureJson(List<EMCustomItem> items, Set<String> writtenKeys) {
        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", "fmmbridge_em");
        root.addProperty("texture_name", "atlas.items");

        JsonObject data = new JsonObject();
        for (EMCustomItem item : items) {
            if (!writtenKeys.contains(item.bedrockTextureKey())) continue;
            if (data.has(item.bedrockTextureKey())) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("textures", "textures/items/" + item.bedrockTextureKey());
            data.add(item.bedrockTextureKey(), entry);
        }
        root.add("texture_data", data);
        return GSON.toJson(root);
    }

    private static UUID deterministicUuid(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            long msb = 0L, lsb = 0L;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
            msb &= ~(0xfL << 12);
            msb |= (0x4L << 12);
            lsb &= ~(0xcL << 60);
            lsb |= (0x8L << 60);
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }
}
