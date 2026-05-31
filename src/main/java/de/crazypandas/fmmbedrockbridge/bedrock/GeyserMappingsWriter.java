package de.crazypandas.fmmbedrockbridge.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GeyserMappingsWriter {

    public static final String BEDROCK_NAMESPACE = "bridge_em";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private GeyserMappingsWriter() {}

    public static void write(List<EMCustomItem> items, Path outFile) throws IOException {
        Path parent = outFile.getParent();
        if (parent != null) Files.createDirectories(parent);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);

        Map<String, JsonArray> byBase = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>(); // base|cmd dedupe

        for (EMCustomItem item : items) {
            String key = item.javaMaterial() + "|" + item.customModelData();
            if (!seen.add(key)) continue;

            String bedrockId = BEDROCK_NAMESPACE + ":" + item.bedrockTextureKey();

            JsonObject definition = new JsonObject();
            definition.addProperty("type", "definition");
            definition.addProperty("bedrock_identifier", bedrockId);
            definition.addProperty("model", bedrockId);

            JsonObject options = new JsonObject();
            options.addProperty("icon", item.bedrockTextureKey());
            definition.add("bedrock_options", options);

            JsonArray predicates = new JsonArray();
            JsonObject predicate = new JsonObject();
            predicate.addProperty("type", "match");
            predicate.addProperty("property", "custom_model_data");
            predicate.addProperty("operator", "==");
            predicate.addProperty("value", String.valueOf(item.customModelData()));
            predicate.addProperty("index", 0);
            predicates.add(predicate);
            definition.add("predicate", predicates);

            byBase.computeIfAbsent(item.javaMaterial(), k -> new JsonArray()).add(definition);
        }

        JsonObject itemsObj = new JsonObject();
        for (Map.Entry<String, JsonArray> e : byBase.entrySet()) itemsObj.add(e.getKey(), e.getValue());
        root.add("items", itemsObj);

        Files.writeString(outFile, GSON.toJson(root));
    }
}
