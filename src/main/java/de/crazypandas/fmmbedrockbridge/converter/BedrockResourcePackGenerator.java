package de.crazypandas.fmmbedrockbridge.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BedrockResourcePackGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BedrockResourcePackGenerator() {
    }

    public static void generate(String modelId, Path skinsDir, Path packDir, double modelScale) throws IOException {
        String normalizedModelId = modelId.toLowerCase(Locale.ROOT);

        Files.createDirectories(packDir);
        writeManifest(packDir.resolve("manifest.json"));

        // Check if animation files exist
        boolean hasAnimations = Files.exists(skinsDir.resolve("animations.json"));
        List<String> animationNames = hasAnimations ? readAnimationNames(skinsDir.resolve("animations.json")) : List.of();

        writeJson(packDir.resolve("entity").resolve(normalizedModelId + ".json"),
                createEntityDefinition(normalizedModelId, modelScale, animationNames));
        writeJson(packDir.resolve("render_controllers").resolve(normalizedModelId + ".json"),
                createRenderController(normalizedModelId));

        Files.createDirectories(packDir.resolve("models").resolve("entity"));
        Files.copy(skinsDir.resolve("geometry.json"),
                packDir.resolve("models").resolve("entity").resolve(normalizedModelId + ".geo.json"),
                StandardCopyOption.REPLACE_EXISTING);

        Files.createDirectories(packDir.resolve("textures").resolve("entity"));
        Files.copy(skinsDir.resolve("texture.png"),
                packDir.resolve("textures").resolve("entity").resolve(normalizedModelId + ".png"),
                StandardCopyOption.REPLACE_EXISTING);

        // Copy animation files if present
        if (hasAnimations) {
            Files.createDirectories(packDir.resolve("animations"));
            Files.copy(skinsDir.resolve("animations.json"),
                    packDir.resolve("animations").resolve(normalizedModelId + ".animation.json"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(skinsDir.resolve("animation_controllers.json"))) {
            Files.createDirectories(packDir.resolve("animation_controllers"));
            Files.copy(skinsDir.resolve("animation_controllers.json"),
                    packDir.resolve("animation_controllers").resolve(normalizedModelId + ".animation_controllers.json"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Path zip(Path packDir) throws IOException {
        Path zipPath = packDir.getParent().resolve("bedrock-pack.zip");
        try (OutputStream outputStream = Files.newOutputStream(zipPath);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
             Stream<Path> pathStream = Files.walk(packDir)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .forEach(path -> addToZip(packDir, path, zipOutputStream));
        }
        return zipPath;
    }

    private static void addToZip(Path packDir, Path file, ZipOutputStream zipOutputStream) {
        try {
            String entryName = packDir.relativize(file).toString().replace('\\', '/');
            zipOutputStream.putNextEntry(new ZipEntry(entryName));
            Files.copy(file, zipOutputStream);
            zipOutputStream.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to zip resource pack file: " + file, e);
        }
    }

    private static void writeManifest(Path manifestPath) throws IOException {
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

    private static Map<String, Object> createEntityDefinition(String modelId, double modelScale, List<String> animationNames) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", "fmmbridge:" + modelId);
        description.put("materials", Map.of("default", "entity_alphatest_change_color_one_sided"));
        description.put("textures", Map.of("default", "textures/entity/" + modelId));
        description.put("geometry", Map.of("default", "geometry.fmmbridge." + modelId));
        description.put("render_controllers", List.of("controller.render.fmmbridge_" + modelId));
        description.put("spawn_egg", Map.of("base_color", "#000000", "overlay_color", "#FFFFFF"));

        // Animation references — controllers go into animations map with _ctrl alias,
        // then scripts.animate lists the aliases so Bedrock evaluates them automatically.
        // Raw strings in animation_controllers[] are invalid Bedrock format.
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

    private static Map<String, Object> createRenderController(String modelId) {
        Map<String, Object> controller = new LinkedHashMap<>();
        controller.put("geometry", "Geometry.default");
        controller.put("materials", List.of(Map.of("*", "Material.default")));
        controller.put("textures", List.of("Texture.default"));

        Map<String, Object> renderControllers = new LinkedHashMap<>();
        renderControllers.put("controller.render.fmmbridge_" + modelId, controller);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.8.0");
        root.put("render_controllers", renderControllers);
        return root;
    }

    /**
     * Reads animation names from a Bedrock .animation.json file.
     */
    private static List<String> readAnimationNames(Path animationFile) {
        try {
            String json = Files.readString(animationFile);
            Map<?, ?> parsed = GSON.fromJson(json, Map.class);
            Map<?, ?> animations = (Map<?, ?>) parsed.get("animations");
            if (animations == null) return List.of();

            List<String> names = new ArrayList<>();
            for (Object key : animations.keySet()) {
                String fullId = (String) key;
                // Extract animation name from "animation.fmmbridge.modelId.animName"
                String[] parts = fullId.split("\\.");
                if (parts.length >= 4) {
                    names.add(parts[parts.length - 1]);
                }
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void writeJson(Path path, Object content) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(content, writer);
        }
    }
}
