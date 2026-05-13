package de.crazypandas.fmmbridgeextension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.geysermc.geyser.api.extension.Extension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates the Bedrock resource pack from per-model input directories.
 */
public class ResourcePackBuilder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path packDir;
    private final Extension extension;

    public ResourcePackBuilder(Path packDir, Extension extension) {
        this.packDir = packDir;
        this.extension = extension;
    }

    public void generatePackFiles(String modelId, Path geometryPath, Path texturePath,
                                   double modelScale, List<String> animationNames,
                                   Path modelDir) throws IOException {
        Path animPath = modelDir.resolve("animations.json");
        Path controllerPath = modelDir.resolve("animation_controllers.json");
        boolean hasAnimations = Files.exists(animPath) && Files.exists(controllerPath);

        writeManifest(packDir.resolve("manifest.json"));
        writeJson(packDir.resolve("entity").resolve(modelId + ".json"),
                createEntityDefinition(modelId, modelScale, animationNames));
        writeJson(packDir.resolve("render_controllers").resolve(modelId + ".json"),
                createRenderController(modelId));

        Files.createDirectories(packDir.resolve("models").resolve("entity"));
        Files.copy(geometryPath,
                packDir.resolve("models").resolve("entity").resolve(modelId + ".geo.json"),
                StandardCopyOption.REPLACE_EXISTING);

        Files.createDirectories(packDir.resolve("textures").resolve("entity"));
        Files.copy(texturePath,
                packDir.resolve("textures").resolve("entity").resolve(modelId + ".png"),
                StandardCopyOption.REPLACE_EXISTING);

        if (hasAnimations) {
            Files.createDirectories(packDir.resolve("animations"));
            Files.copy(animPath,
                    packDir.resolve("animations").resolve(modelId + ".animation.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(packDir.resolve("animation_controllers"));
            // Use just modelId + ".json" (not ".animation_controllers.json") — Bedrock rejects
            // paths over 80 chars, and the longer suffix pushed many model names over the limit.
            Files.copy(controllerPath,
                    packDir.resolve("animation_controllers").resolve(modelId + ".json"),
                    StandardCopyOption.REPLACE_EXISTING);
            extension.logger().info("FMMBridge: " + modelId + " has " + animationNames.size() + " animations");
        }
    }

    /**
     * Reads em-items.json from inputDir, copies PNG textures into the pack,
     * and writes textures/item_texture.json. Call before zip().
     * Returns only the successfully embedded entries (those with valid textures).
     */
    public List<EmItemEntry> embedEliteItems(Path inputDir) {
        Path emItemsJson = inputDir.resolve("em-items.json");
        if (!Files.exists(emItemsJson)) {
            extension.logger().info("[Phase 7.2b] No em-items.json in input/ — skipping EM item embedding.");
            return List.of();
        }

        List<EmItemEntry> entries = List.of();
        List<EmItemEntry> embedded = new ArrayList<>();
        try {
            String json = Files.readString(emItemsJson);
            com.google.gson.reflect.TypeToken<List<EmItemEntry>> token = new com.google.gson.reflect.TypeToken<>() {};
            entries = GSON.fromJson(json, token.getType());

            Path itemTexturesDir = packDir.resolve("textures/em");
            Files.createDirectories(itemTexturesDir);

            Map<String, Object> textureData = new LinkedHashMap<>();

            for (EmItemEntry entry : entries) {
                Path srcTexture = inputDir.resolve(entry.sourceTexturePath());
                if (!Files.exists(srcTexture)) {
                    extension.logger().warning("[Phase 7.2b] Texture not found: " + srcTexture);
                    continue;
                }
                // Destination: textures/em/<bedrockTextureKey>.png
                // bedrockTextureKey is already "em_foo", filename becomes "em_foo.png"
                String filename = entry.bedrockTextureKey() + ".png";
                Path dest = itemTexturesDir.resolve(filename);
                Files.copy(srcTexture, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // item_texture.json entry: key → "textures/em/<key>" (no .png extension)
                textureData.put(entry.bedrockTextureKey(),
                        Map.of("textures", "textures/em/" + entry.bedrockTextureKey()));

                // Track successfully embedded entry
                embedded.add(entry);
            }

            // Write textures/item_texture.json
            Map<String, Object> itemTextureRoot = new LinkedHashMap<>();
            itemTextureRoot.put("resource_pack_name", "fmmbridge");
            itemTextureRoot.put("texture_name", "atlas.items");
            itemTextureRoot.put("texture_data", textureData);
            writeJson(packDir.resolve("textures/item_texture.json"), itemTextureRoot);

            extension.logger().info("[Phase 7.2b] Embedded " + embedded.size() + " EM item textures into Bedrock pack.");
        } catch (Exception e) {
            extension.logger().error("[Phase 7.2b] Failed to embed EM items: " + e.getMessage(), e);
        }
        return embedded;
    }

    public void zip(Path zipPath) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(zipPath);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
             Stream<Path> pathStream = Files.walk(packDir)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> addToZip(path, zipOutputStream));
        }
    }

    private void addToZip(Path file, ZipOutputStream zipOutputStream) {
        try {
            String entryName = packDir.relativize(file).toString().replace('\\', '/');
            zipOutputStream.putNextEntry(new ZipEntry(entryName));
            Files.copy(file, zipOutputStream);
            zipOutputStream.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to zip file " + file, e);
        }
    }

    private void writeGearAnimations(Path packDir) throws IOException {
        // Hand-positioning animations for all gear items.
        // All gear geos share the same bone names (geyser_custom_x/y/z/geyser_custom),
        // so a single animation set suffices. Values are the GeyserUtils/NitroSetups
        // standard for simple flat-sprite items held in hand.
        Map<String, Object> tp3Main = boneAnim(
                new double[]{0, 0, 0}, new double[]{0, 4, 2.5}, new double[]{0.85, 0.85, 0.85},
                new double[]{0, -90, 0}, null, null,
                new double[]{0, 0, 55}, null, null,
                new double[]{90, 0, 0}, new double[]{0, 13, -3}, null);
        Map<String, Object> tp3Off = boneAnim(
                new double[]{0, 0, 0}, new double[]{0, 4, 2.5}, new double[]{0.85, 0.85, 0.85},
                new double[]{0, 90, 0}, null, null,
                new double[]{0, 0, -55}, null, null,
                new double[]{90, 0, 0}, new double[]{0, 13, -3}, null);
        Map<String, Object> tp3Head = boneAnimSimple(0.625, new double[]{0, 19.9, 0});
        Map<String, Object> fp1Main = boneAnim(
                null, new double[]{0, 1.6, -0.8}, new double[]{0.68, 0.68, 0.68},
                new double[]{0, -90, 0}, null, null,
                new double[]{0, 0, 25}, null, null,
                new double[]{53.79601, 51.7101, -83.00307}, new double[]{-2, 12, 5}, new double[]{1.5, 1.5, 1.5});
        Map<String, Object> fp1Off = boneAnim(
                null, new double[]{0, 1.6, -0.8}, new double[]{0.68, 0.68, 0.68},
                new double[]{0, 90, 0}, null, null,
                new double[]{0, 0, -25}, null, null,
                new double[]{90, 60, -40}, new double[]{4, 10, 4}, new double[]{1.5, 1.5, 1.5});
        Map<String, Object> disable = new LinkedHashMap<>();
        disable.put("loop", true);
        Map<String, Object> disableBones = new LinkedHashMap<>();
        Map<String, Object> disableX = new LinkedHashMap<>();
        disableX.put("scale", 0);
        disableBones.put("geyser_custom_x", disableX);
        disable.put("bones", disableBones);

        Map<String, Object> allAnims = new LinkedHashMap<>();
        allAnims.put("animation.fmmbridge.gear.thirdperson_main_hand", tp3Main);
        allAnims.put("animation.fmmbridge.gear.thirdperson_off_hand",  tp3Off);
        allAnims.put("animation.fmmbridge.gear.head",                  tp3Head);
        allAnims.put("animation.fmmbridge.gear.firstperson_main_hand", fp1Main);
        allAnims.put("animation.fmmbridge.gear.firstperson_off_hand",  fp1Off);
        allAnims.put("animation.fmmbridge.gear.disable",               disable);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.8.0");
        root.put("animations", allAnims);
        writeJson(packDir.resolve("animations/fmmbridge_gear.json"), root);
    }

    private Map<String, Object> boneAnim(
            double[] xRot, double[] xPos, double[] xScale,
            double[] yRot, double[] yPos, double[] yScale,
            double[] zRot, double[] zPos, double[] zScale,
            double[] gcRot, double[] gcPos, double[] gcScale) {
        Map<String, Object> anim = new LinkedHashMap<>();
        anim.put("loop", true);
        Map<String, Object> bones = new LinkedHashMap<>();
        bones.put("geyser_custom_x", buildBone(xRot, xPos, xScale));
        bones.put("geyser_custom_y", buildBone(yRot, yPos, yScale));
        bones.put("geyser_custom_z", buildBone(zRot, zPos, zScale));
        bones.put("geyser_custom",   buildBone(gcRot, gcPos, gcScale));
        anim.put("bones", bones);
        return anim;
    }

    private Map<String, Object> boneAnimSimple(double xScale, double[] gcPos) {
        Map<String, Object> anim = new LinkedHashMap<>();
        anim.put("loop", true);
        Map<String, Object> bones = new LinkedHashMap<>();
        Map<String, Object> bx = new LinkedHashMap<>();
        bx.put("scale", xScale);
        bones.put("geyser_custom_x", bx);
        Map<String, Object> bgc = new LinkedHashMap<>();
        bgc.put("position", gcPos);
        bones.put("geyser_custom", bgc);
        anim.put("bones", bones);
        return anim;
    }

    private Map<String, Object> buildBone(double[] rot, double[] pos, double[] scale) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (rot   != null) b.put("rotation", rot);
        if (pos   != null) b.put("position", pos);
        if (scale != null) b.put("scale",    scale);
        return b;
    }

    private void writeManifest(Path manifestPath) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", 2);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", "FMMBridge Resource Pack");
        header.put("uuid", UUID.randomUUID().toString());
        header.put("version", List.of(1, 0, 0));
        header.put("min_engine_version", List.of(1, 20, 0));
        root.put("header", header);

        Map<String, Object> module = new LinkedHashMap<>();
        module.put("type", "resources");
        module.put("uuid", UUID.randomUUID().toString());
        module.put("version", List.of(1, 0, 0));
        root.put("modules", List.of(module));

        writeJson(manifestPath, root);
    }

    private Map<String, Object> createEntityDefinition(String modelId, double modelScale,
                                                        List<String> animationNames) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", "fmmbridge:" + modelId);
        description.put("materials", Map.of("default", "entity_alphatest_change_color_one_sided"));
        description.put("textures", Map.of("default", "textures/entity/" + modelId));
        description.put("geometry", Map.of("default", "geometry.fmmbridge." + modelId));
        description.put("render_controllers", List.of("controller.render.fmmbridge_" + modelId));
        description.put("spawn_egg", Map.of("base_color", "#000000", "overlay_color", "#FFFFFF"));

        Map<String, String> animations = new LinkedHashMap<>();
        List<String> animateList = new ArrayList<>();
        for (String animName : animationNames) {
            animations.put(animName, "animation.fmmbridge." + modelId + "." + animName);
            animations.put(animName + "_ctrl", "controller.animation.fmmbridge." + modelId + "." + animName);
            animateList.add(animName + "_ctrl");
        }
        if (!animations.isEmpty()) {
            description.put("animations", animations);
        }

        Map<String, Object> scripts = new LinkedHashMap<>();
        if (modelScale != 1.0) {
            scripts.put("scale", String.valueOf(modelScale));
        }
        if (!animateList.isEmpty()) {
            scripts.put("animate", animateList);
        }
        if (!scripts.isEmpty()) {
            description.put("scripts", scripts);
        }

        Map<String, Object> clientEntity = new LinkedHashMap<>();
        clientEntity.put("description", description);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.10.0");
        root.put("minecraft:client_entity", clientEntity);
        return root;
    }

    private Map<String, Object> createRenderController(String modelId) {
        Map<String, Object> controller = new LinkedHashMap<>();
        controller.put("geometry", "Geometry.default");
        controller.put("materials", List.of(Map.of("*", "Material.default")));
        controller.put("textures", List.of("Texture.default"));

        Map<String, Object> controllers = new LinkedHashMap<>();
        controllers.put("controller.render.fmmbridge_" + modelId, controller);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.8.0");
        root.put("render_controllers", controllers);
        return root;
    }

    private void writeJson(Path path, Object content) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(content, writer);
        }
    }

    /** Mirrors EMCustomItem for deserialization within the extension. */
    public record EmItemEntry(
            String javaMaterial,
            int customModelData,
            String sourceTexturePath,  // relative path like "em-item-textures/em_foo.png"
            String bedrockTextureKey   // e.g. "em_foo"
    ) {}

    /** Mirrors EMGearItem for deserialisation within the extension. */
    public record EmGearEntry(
            String javaMaterial,
            int customModelData,
            String bedrockKey,
            String javaItemModel,     // e.g. "elitemobs:gear/bronze_sword"
            String sourceModelPath,   // relative: "em-gear-models/em_bronze_sword.json"
            String sourceTexturePath  // relative: "em-gear-textures/em_bronze_sword.png"
    ) {}

    /**
     * Reads em-gear-items.json from inputDir, converts Java model elements to Bedrock geo.json,
     * generates attachable definitions, copies textures, and merges entries into item_texture.json.
     * Must be called AFTER embedEliteItems() so the item_texture.json merge works correctly.
     * Returns only successfully embedded entries.
     */
    @SuppressWarnings("unchecked")
    public List<EmGearEntry> embedGearItems(Path inputDir) {
        Path emGearJson = inputDir.resolve("em-gear-items.json");
        if (!Files.exists(emGearJson)) {
            extension.logger().info("[Phase 7.2c] No em-gear-items.json in input/ — skipping 3D gear embedding.");
            return List.of();
        }

        List<EmGearEntry> embedded = new ArrayList<>();
        try {
            String json = Files.readString(emGearJson);
            com.google.gson.reflect.TypeToken<List<EmGearEntry>> token = new com.google.gson.reflect.TypeToken<>() {};
            List<EmGearEntry> entries = GSON.fromJson(json, token.getType());

            JavaItemGeometryConverter converter = new JavaItemGeometryConverter();

            // Write shared hand-positioning animation file for all gear items.
            // Values match the GeyserUtils / NitroSetups item convention (geyser_custom_x/y/z bones).
            writeGearAnimations(packDir);

            // Read existing item_texture.json (written by embedEliteItems) or start fresh
            Path itemTextureJsonPath = packDir.resolve("textures/item_texture.json");
            Map<String, Object> itemTextureRoot = new LinkedHashMap<>();
            Map<String, Object> textureData = new LinkedHashMap<>();

            if (Files.exists(itemTextureJsonPath)) {
                Map<?, ?> existing = GSON.fromJson(Files.readString(itemTextureJsonPath), Map.class);
                itemTextureRoot.putAll((Map<String, Object>) existing);
                Object existingData = existing.get("texture_data");
                if (existingData instanceof Map) {
                    textureData.putAll((Map<String, Object>) existingData);
                }
            } else {
                itemTextureRoot.put("resource_pack_name", "fmmbridge");
                itemTextureRoot.put("texture_name", "atlas.items");
            }

            // Item geometry goes in models/entity/ — the standard Bedrock path for geometry
            // loaded by attachables; models/blocks/ is for block custom geometry only.
            Path geoDir = packDir.resolve("models/entity");
            Files.createDirectories(geoDir);

            for (EmGearEntry entry : entries) {
                try {
                    // 1. Read and convert Java model JSON → Bedrock geometry
                    Path modelSrc = inputDir.resolve(entry.sourceModelPath());
                    if (!Files.exists(modelSrc)) {
                        extension.logger().warning("[Phase 7.2c] Model not found: " + modelSrc);
                        continue;
                    }
                    com.google.gson.JsonObject javaModel = GSON.fromJson(
                            Files.readString(modelSrc), com.google.gson.JsonObject.class);
                    Map<String, Object> geoJson = converter.convertToGeo(javaModel, entry.bedrockKey());
                    writeJson(geoDir.resolve(entry.bedrockKey() + ".geo.json"), geoJson);

                    // 2. Generate attachable definition
                    Map<String, Object> attachable = converter.generateAttachable(entry.bedrockKey());
                    Path attachDir = packDir.resolve("attachables");
                    Files.createDirectories(attachDir);
                    writeJson(attachDir.resolve("fmmbridge_" + entry.bedrockKey() + ".json"), attachable);

                    // 3. Copy texture PNG — crop to declared texture_size if PNG is animated (taller than declared)
                    Path texSrc = inputDir.resolve(entry.sourceTexturePath());
                    if (!Files.exists(texSrc)) {
                        extension.logger().warning("[Phase 7.2c] Texture not found: " + texSrc);
                        continue;
                    }
                    int texW = 64, texH = 64;
                    if (javaModel.has("texture_size")) {
                        com.google.gson.JsonArray ts = javaModel.getAsJsonArray("texture_size");
                        texW = ts.get(0).getAsInt();
                        texH = ts.get(1).getAsInt();
                    }
                    Path texDir = packDir.resolve("textures/items/em/gear");
                    Files.createDirectories(texDir);
                    Path texDest = texDir.resolve(entry.bedrockKey() + ".png");
                    BufferedImage fullImg = ImageIO.read(texSrc.toFile());
                    if (fullImg != null && fullImg.getHeight() > texH) {
                        BufferedImage cropped = fullImg.getSubimage(0, 0, texW, texH);
                        ImageIO.write(cropped, "PNG", texDest.toFile());
                    } else {
                        Files.copy(texSrc, texDest, StandardCopyOption.REPLACE_EXISTING);
                    }

                    // 4. Add to item_texture.json (for inventory display)
                    textureData.put(entry.bedrockKey(),
                            Map.of("textures", "textures/items/em/gear/" + entry.bedrockKey()));

                    embedded.add(entry);
                } catch (Exception e) {
                    extension.logger().warning("[Phase 7.2c] Failed to embed " + entry.bedrockKey() + ": " + e.getMessage());
                }
            }

            // Write merged item_texture.json
            itemTextureRoot.put("texture_data", textureData);
            writeJson(itemTextureJsonPath, itemTextureRoot);

            extension.logger().info("[Phase 7.2c] Embedded " + embedded.size() + " EM gear items into Bedrock pack.");
        } catch (Exception e) {
            extension.logger().error("[Phase 7.2c] Failed to embed gear items: " + e.getMessage(), e);
        }
        return embedded;
    }

    /** Minimal test geo — single sword-shaped cube, integer UVs, 16x16 texture space. */
    private Map<String, Object> buildTestGeo(String bedrockKey) {
        Map<String, Object> cube = new LinkedHashMap<>();
        cube.put("origin", new int[]{-1, -6, -1});
        cube.put("size",   new int[]{2, 12, 2});
        Map<String, Object> faces = new LinkedHashMap<>();
        for (String f : List.of("north","south","east","west","up","down")) {
            Map<String, Object> fd = new LinkedHashMap<>();
            fd.put("uv",      new int[]{0, 0});
            fd.put("uv_size", new int[]{2, 12});
            faces.put(f, fd);
        }
        cube.put("uv", faces);

        Map<String, Object> bone = new LinkedHashMap<>();
        bone.put("name",  "root");
        bone.put("pivot", new int[]{0, 0, 0});
        bone.put("cubes", List.of(cube));

        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("identifier",            "geometry.fmmbridge." + bedrockKey);
        desc.put("texture_width",         16);
        desc.put("texture_height",        16);
        desc.put("visible_bounds_width",  4);
        desc.put("visible_bounds_height", 4);
        desc.put("visible_bounds_offset", new int[]{0, 2, 0});

        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("description", desc);
        geo.put("bones", List.of(bone));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.12.0");
        root.put("minecraft:geometry", List.of(geo));
        return root;
    }
}
