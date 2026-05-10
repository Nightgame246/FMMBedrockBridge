package de.crazypandas.fmmbedrockbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Scans the EliteMobs Java resource pack for custom_model_data overrides.
 * Only includes 2D items (item/generated parent, layer0 texture, no elements).
 * 3D gear items (with elements) are skipped — handled in Phase 7.2c.
 *
 * Expected pack structure (multiple sub-packs under packRoot):
 *   packRoot/em_rsp_defaults/assets/minecraft/models/item/<base>.json
 *   packRoot/em_rsp_defaults/assets/<ns>/models/<path>.json  (override target)
 *   packRoot/em_rsp_defaults/assets/<ns>/textures/<path>.png
 */
public class EliteMobsItemScanner {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private static final Gson GSON = new Gson();

    private final Path packRoot;

    public EliteMobsItemScanner(Path packRoot) {
        this.packRoot = packRoot;
    }

    /**
     * Scans all sub-packs and returns 2D custom item mappings.
     * Returns empty list if pack root doesn't exist.
     */
    public List<EMCustomItem> scan() {
        List<EMCustomItem> result = new ArrayList<>();

        if (!Files.isDirectory(packRoot)) {
            log.warning("[ItemScanner] EM resource pack dir not found at " + packRoot + " — skipping.");
            return result;
        }

        try (var subPacks = Files.list(packRoot)) {
            subPacks.filter(Files::isDirectory)
                    .sorted()
                    .forEach(subPack -> scanSubPack(subPack, result));
        } catch (IOException e) {
            log.warning("[ItemScanner] Failed to list sub-packs: " + e.getMessage());
        }

        log.info("[ItemScanner] Found " + result.size() + " 2D EM custom items (3D gear skipped → Phase 7.2c).");
        return result;
    }

    private void scanSubPack(Path subPack, List<EMCustomItem> result) {
        Path itemModelsDir = subPack.resolve("assets/minecraft/models/item");
        if (!Files.isDirectory(itemModelsDir)) return;

        try (var files = Files.list(itemModelsDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(modelFile -> scanModelFile(subPack, modelFile, result));
        } catch (IOException e) {
            log.warning("[ItemScanner] Failed to list items in " + subPack.getFileName() + ": " + e.getMessage());
        }
    }

    private void scanModelFile(Path subPack, Path modelFile, List<EMCustomItem> result) {
        try {
            String baseMaterial = "minecraft:" + modelFile.getFileName().toString().replace(".json", "");
            String json = Files.readString(modelFile);
            JsonObject root = GSON.fromJson(json, JsonObject.class);

            if (!root.has("overrides")) return;
            JsonArray overrides = root.getAsJsonArray("overrides");

            for (JsonElement el : overrides) {
                JsonObject override = el.getAsJsonObject();
                JsonObject predicate = override.getAsJsonObject("predicate");
                if (predicate == null || !predicate.has("custom_model_data")) continue;

                int cmd = predicate.get("custom_model_data").getAsInt();
                String modelRef = override.get("model").getAsString();

                // Resolve texture — skip if 3D (has elements)
                Path texturePath = resolveTexture2DOnly(subPack, modelRef);
                if (texturePath == null) continue; // 3D or not found → skip

                // Key based on PNG filename for deduplication: multiple CMD values sharing
                // the same texture get the same Bedrock key (intentional).
                String texBaseName = texturePath.getFileName().toString().replace(".png", "");
                String textureKey = "em_" + texBaseName;

                result.add(new EMCustomItem(baseMaterial, cmd, texturePath.toString(), textureKey));
            }
        } catch (Exception e) {
            log.warning("[ItemScanner] Error scanning " + modelFile + ": " + e.getMessage());
        }
    }

    /**
     * Follows model reference to find texture path.
     * Returns null if the model is 3D (has "elements") or texture not found.
     */
    private Path resolveTexture2DOnly(Path subPack, String modelRef) {
        String[] parts = modelRef.split(":");
        if (parts.length != 2) return null;
        String namespace = parts[0];
        String itemPath = parts[1];

        Path targetModel = subPack.resolve("assets/" + namespace + "/models/" + itemPath + ".json");
        if (!Files.exists(targetModel)) return null;

        try {
            String json = Files.readString(targetModel);
            JsonObject model = GSON.fromJson(json, JsonObject.class);

            // Skip 3D items
            if (model.has("elements")) return null;

            if (!model.has("textures")) return null;
            JsonObject textures = model.getAsJsonObject("textures");

            String layer0 = null;
            if (textures.has("layer0")) layer0 = textures.get("layer0").getAsString();
            else if (textures.has("all")) layer0 = textures.get("all").getAsString();
            if (layer0 == null) return null;

            // layer0: "namespace:path" or "path"
            String[] texParts = layer0.split(":");
            String texNs = texParts.length == 2 ? texParts[0] : namespace;
            String texPath = texParts.length == 2 ? texParts[1] : texParts[0];

            Path pngPath = subPack.resolve("assets/" + texNs + "/textures/" + texPath + ".png");
            if (!Files.exists(pngPath)) return null;
            return pngPath;
        } catch (Exception e) {
            log.warning("[ItemScanner] Could not resolve model " + modelRef + ": " + e.getMessage());
            return null;
        }
    }
}
