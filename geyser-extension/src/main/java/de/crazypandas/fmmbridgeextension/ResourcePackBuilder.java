package de.crazypandas.fmmbridgeextension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.geysermc.geyser.api.extension.Extension;

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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
            Files.copy(controllerPath,
                    packDir.resolve("animation_controllers").resolve(modelId + ".animation_controllers.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            extension.logger().info("FMMBridge: " + modelId + " has " + animationNames.size() + " animations");
        }
    }

    public void zip(Path zipPath) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(zipPath);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
             Stream<Path> pathStream = Files.walk(packDir)) {
            pathStream
                    .filter(Files::isRegularFile)
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
}
