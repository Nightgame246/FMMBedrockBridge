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
import java.util.Map;
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

    private static final Logger log = resolveLogger();
    private static final Gson GSON = new Gson();

    // Falls back to a standalone logger when the plugin singleton isn't available
    // (e.g. in unit tests), so this class can be loaded without a running server.
    private static Logger resolveLogger() {
        FMMBedrockBridge instance = FMMBedrockBridge.getInstance();
        return instance != null ? instance.getLogger() : Logger.getLogger("FMMBedrockBridge");
    }

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

    /**
     * Scans all sub-packs and returns 3D gear item mappings.
     * Includes: models with "elements" + "gear/" path + no "_icon" suffix + not iron_horse_armor.
     * Returns empty list if pack root doesn't exist.
     */
    public List<EMGearItem> scan3DGear() {
        List<EMGearItem> result = new ArrayList<>();

        if (!Files.isDirectory(packRoot)) {
            log.warning("[ItemScanner] EM resource pack dir not found at " + packRoot + " — skipping 3D gear scan.");
            return result;
        }

        try (var subPacks = Files.list(packRoot)) {
            subPacks.filter(Files::isDirectory)
                    .sorted()
                    .forEach(subPack -> scanSubPackGear(subPack, result));
        } catch (IOException e) {
            log.warning("[ItemScanner] Failed to list sub-packs for gear scan: " + e.getMessage());
        }

        log.info("[ItemScanner] Found " + result.size() + " 3D EM gear items.");
        return result;
    }

    private void scanSubPackGear(Path subPack, List<EMGearItem> result) {
        Path itemModelsDir = subPack.resolve("assets/minecraft/models/item");
        if (!Files.isDirectory(itemModelsDir)) return;

        try (var files = Files.list(itemModelsDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(modelFile -> scanModelFileGear(subPack, modelFile, result));
        } catch (IOException e) {
            log.warning("[ItemScanner] Failed to list items for gear in " + subPack.getFileName() + ": " + e.getMessage());
        }
    }

    private void scanModelFileGear(Path subPack, Path modelFile, List<EMGearItem> result) {
        String baseName = modelFile.getFileName().toString().replace(".json", "");
        if (baseName.equals("iron_horse_armor")) return; // helmets → 7.2d

        try {
            String baseMaterial = "minecraft:" + baseName;
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

                // Only gear/ path models
                if (!modelRef.contains("gear/")) continue;

                // Last segment of path, e.g. "bronze_sword"
                String modelName = modelRef.contains("/") ? modelRef.substring(modelRef.lastIndexOf('/') + 1) : modelRef;
                if (modelName.contains("_icon")) continue; // GUI-only icons → skip

                // Deduplicate: skip if bedrockKey already in result
                String bedrockKey = "em_" + modelName;
                boolean duplicate = result.stream().anyMatch(g -> g.bedrockKey().equals(bedrockKey));
                if (duplicate) continue;

                // Resolve model file and texture
                GearModelInfo info = resolveGear3D(subPack, modelRef);
                if (info == null) continue;

                // EM's item_model identifier is the modelRef as-is — that's what
                // EM sets on real gear items in-game. If the ref lacks a namespace,
                // Java/Bedrock defaults to "minecraft:"; but EM uses "elitemobs:".
                String javaItemModel = modelRef.contains(":") ? modelRef : "minecraft:" + modelRef;
                result.add(new EMGearItem(baseMaterial, cmd, bedrockKey, javaItemModel,
                        info.modelPath().toString(), info.texturePath().toString()));
            }
        } catch (Exception e) {
            log.warning("[ItemScanner] Error scanning gear in " + modelFile + ": " + e.getMessage());
        }
    }

    /**
     * Picks the texture reference from a 3D model's "textures" object.
     * Blockbench assigns arbitrary numeric keys ("0", "29", "1", ...), so we
     * take the first entry that isn't the "particle" key rather than assuming "0".
     * Returns null if there is no usable texture entry.
     */
    static String pickGearTextureRef(JsonObject textures) {
        for (Map.Entry<String, JsonElement> e : textures.entrySet()) {
            if (e.getKey().equals("particle")) continue;
            return e.getValue().getAsString();
        }
        return null;
    }

    /**
     * Resolves a gear model reference, verifying it has "elements" (3D).
     * Returns null if not 3D, not found, or texture missing.
     */
    private GearModelInfo resolveGear3D(Path subPack, String modelRef) {
        String[] parts = modelRef.split(":");
        if (parts.length != 2) return null;
        String namespace = parts[0];
        String itemPath = parts[1];

        Path targetModel = subPack.resolve("assets/" + namespace + "/models/" + itemPath + ".json");
        if (!Files.exists(targetModel)) return null;

        try {
            String json = Files.readString(targetModel);
            JsonObject model = GSON.fromJson(json, JsonObject.class);

            if (!model.has("elements")) return null; // 2D → skip

            if (!model.has("textures")) return null;
            JsonObject textures = model.getAsJsonObject("textures");

            // 3D models use a numeric texture key (Blockbench-assigned: "0", "29", ...) — not "layer0"
            String texRef = pickGearTextureRef(textures);
            if (texRef == null) return null;

            String[] texParts = texRef.split(":");
            String texNs = texParts.length == 2 ? texParts[0] : namespace;
            String texPath = texParts.length == 2 ? texParts[1] : texParts[0];

            Path pngPath = subPack.resolve("assets/" + texNs + "/textures/" + texPath + ".png");
            if (!Files.exists(pngPath)) return null;

            return new GearModelInfo(targetModel, pngPath);
        } catch (Exception e) {
            log.warning("[ItemScanner] Could not resolve gear model " + modelRef + ": " + e.getMessage());
            return null;
        }
    }

    private record GearModelInfo(Path modelPath, Path texturePath) {}
}
