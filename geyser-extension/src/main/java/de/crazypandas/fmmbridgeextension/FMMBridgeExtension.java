package de.crazypandas.fmmbridgeextension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FMMBridgeExtension implements Extension {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        Path inputDir = this.dataFolder().resolve("input");
        Path packDir = this.dataFolder().resolve("generated-pack");
        Path zipPath = this.dataFolder().resolve("generated-pack.zip");

        try {
            Files.createDirectories(inputDir);
            Files.createDirectories(packDir);

            try (Stream<Path> stream = Files.list(inputDir)) {
                stream.filter(Files::isDirectory).forEach(modelDir -> processModelDirectory(modelDir, packDir));
            }

            zip(packDir, zipPath);
            this.logger().info("Generated Bedrock resource pack at " + zipPath);
        } catch (Exception e) {
            this.logger().error("Failed to generate Bedrock resource pack: " + e.getMessage(), e);
        }
    }

    @Subscribe
    public void onDefineResourcePacks(GeyserDefineResourcePacksEvent event) {
        Path zipPath = this.dataFolder().resolve("generated-pack.zip");
        if (!Files.exists(zipPath)) {
            return;
        }

        try {
            ResourcePack pack = ResourcePack.create(PackCodec.path(zipPath));
            event.register(pack);
            this.logger().info("Registered generated Bedrock resource pack.");
        } catch (Exception e) {
            this.logger().error("Failed to register generated Bedrock resource pack: " + e.getMessage(), e);
        }
    }

    private void processModelDirectory(Path modelDir, Path packDir) {
        String modelId = modelDir.getFileName().toString().toLowerCase(Locale.ROOT);
        Path geometryPath = modelDir.resolve("geometry.json");
        Path texturePath = modelDir.resolve("texture.png");

        if (!Files.exists(geometryPath) || !Files.exists(texturePath)) {
            this.logger().info("Skipping " + modelId + " because geometry.json or texture.png is missing.");
            return;
        }

        try {
            registerCustomEntity(modelId);
            generatePackFiles(modelId, geometryPath, texturePath, packDir);
            this.logger().info("Prepared Bedrock assets for " + modelId);
        } catch (Exception e) {
            this.logger().error("Failed to prepare model " + modelId + ": " + e.getMessage(), e);
        }
    }

    private void registerCustomEntity(String modelId) throws ReflectiveOperationException {
        Class.forName("me.zimzaza4.geyserutils.geyser.GeyserUtils")
                .getMethod("addCustomEntity", String.class)
                .invoke(null, "fmmbridge:" + modelId);
    }

    private void generatePackFiles(String modelId, Path geometryPath, Path texturePath, Path packDir) throws IOException {
        writeManifest(packDir.resolve("manifest.json"));
        writeJson(packDir.resolve("entity").resolve(modelId + ".json"), createEntityDefinition(modelId));
        writeJson(packDir.resolve("render_controllers").resolve(modelId + ".json"), createRenderController(modelId));

        Files.createDirectories(packDir.resolve("models").resolve("entity"));
        Files.copy(geometryPath,
                packDir.resolve("models").resolve("entity").resolve(modelId + ".geo.json"),
                StandardCopyOption.REPLACE_EXISTING);

        Files.createDirectories(packDir.resolve("textures").resolve("entity"));
        Files.copy(texturePath,
                packDir.resolve("textures").resolve("entity").resolve(modelId + ".png"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeManifest(Path manifestPath) throws IOException {
        if (Files.exists(manifestPath)) {
            return;
        }

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

    private Map<String, Object> createEntityDefinition(String modelId) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", "fmmbridge:" + modelId);
        description.put("materials", Map.of("default", "entity_alphatest_change_color"));
        description.put("textures", Map.of("default", "textures/entity/" + modelId));
        description.put("geometry", Map.of("default", "geometry.fmmbridge." + modelId));
        description.put("render_controllers", List.of("controller.render.fmmbridge_" + modelId));
        description.put("spawn_egg", Map.of("base_color", "#000000", "overlay_color", "#FFFFFF"));

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

    private void zip(Path packDir, Path zipPath) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(zipPath);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
             Stream<Path> pathStream = Files.walk(packDir)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .forEach(path -> addToZip(packDir, path, zipOutputStream));
        }
    }

    private void addToZip(Path packDir, Path file, ZipOutputStream zipOutputStream) {
        try {
            String entryName = packDir.relativize(file).toString().replace('\\', '/');
            zipOutputStream.putNextEntry(new ZipEntry(entryName));
            Files.copy(file, zipOutputStream);
            zipOutputStream.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to zip file " + file, e);
        }
    }
}
