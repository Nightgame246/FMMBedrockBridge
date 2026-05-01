package de.crazypandas.fmmbridgeextension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public class FMMBridgeExtension implements Extension {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final java.util.List<String> registeredModels = new java.util.ArrayList<>();
    private final EntityRegistrar entityRegistrar = new EntityRegistrar(this);
    private final DownstreamMonitor downstreamMonitor = new DownstreamMonitor(this);

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        Path inputDir = this.dataFolder().resolve("input");
        Path packDir = this.dataFolder().resolve("generated-pack");
        Path zipPath = this.dataFolder().resolve("generated-pack.zip");

        try {
            Files.createDirectories(inputDir);
            // Clean the pack directory on every start so stale files from previous builds
            // (e.g. old "*.animation_controllers.json" entries) don't end up in the new zip.
            if (Files.exists(packDir)) {
                try (var walk = Files.walk(packDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (java.io.IOException ignored) {} });
                }
            }
            Files.createDirectories(packDir);

            ResourcePackBuilder packBuilder = new ResourcePackBuilder(packDir, this);

            try (Stream<Path> stream = Files.list(inputDir)) {
                stream.filter(Files::isDirectory).forEach(modelDir ->
                        processModelDirectory(modelDir, packBuilder));
            }

            packBuilder.zip(zipPath);
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

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        downstreamMonitor.init();
    }

    @Subscribe
    public void onSessionLogin(SessionLoginEvent event) {
        this.logger().info("FMMBridge: Session login detected for " + event.connection());
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        downstreamMonitor.onSessionDisconnect(event.connection());
    }

    @Override
    public void disable() {
        downstreamMonitor.shutdown();
        Extension.super.disable();
    }

    private void processModelDirectory(Path modelDir, ResourcePackBuilder packBuilder) {
        String modelId = modelDir.getFileName().toString().toLowerCase(Locale.ROOT);
        Path geometryPath = modelDir.resolve("geometry.json");
        Path texturePath = modelDir.resolve("texture.png");

        if (!Files.exists(geometryPath) || !Files.exists(texturePath)) {
            this.logger().info("Skipping " + modelId + " because geometry.json or texture.png is missing.");
            return;
        }

        double modelScale = readModelScale(modelDir);

        try {
            if (entityRegistrar.registerEntity(modelId)) {
                registeredModels.add(modelId);
                this.logger().info("FMMBridgeExtension: Registered model " + modelId + " for Bedrock (scale=" + modelScale + ")");
            } else {
                this.logger().warning("FMMBridgeExtension: Failed to register model " + modelId);
                return;
            }

            // Register animation properties BEFORE generatePackFiles
            Path animPath = modelDir.resolve("animations.json");
            List<String> animNames = List.of();
            if (Files.exists(animPath)) {
                animNames = readAnimationNames(animPath);
                entityRegistrar.registerAnimationProperties(modelId, animNames.size());
            }

            packBuilder.generatePackFiles(modelId, geometryPath, texturePath, modelScale, animNames, modelDir);
            this.logger().info("Prepared Bedrock assets for " + modelId);
        } catch (Exception e) {
            this.logger().error("Failed to prepare model " + modelId + ": " + e.getMessage(), e);
        }
    }

    private double readModelScale(Path modelDir) {
        Path configPath = modelDir.resolve("model-config.json");
        if (!Files.exists(configPath)) return 1.6;
        try {
            String json = Files.readString(configPath);
            Map<?, ?> config = GSON.fromJson(json, Map.class);
            Object scale = config.get("model_scale");
            if (scale instanceof Number) return ((Number) scale).doubleValue();
        } catch (Exception e) {
            this.logger().warning("FMMBridge: Could not read model-config.json from " + modelDir + ": " + e.getMessage());
        }
        return 1.6;
    }

    private List<String> readAnimationNames(Path animationFile) {
        try {
            String json = Files.readString(animationFile);
            Map<?, ?> parsed = GSON.fromJson(json, Map.class);
            Map<?, ?> animations = (Map<?, ?>) parsed.get("animations");
            if (animations == null) return List.of();

            java.util.List<String> names = new java.util.ArrayList<>();
            for (Object key : animations.keySet()) {
                String fullId = (String) key;
                String[] parts = fullId.split("\\.");
                if (parts.length >= 4) {
                    names.add(parts[parts.length - 1]);
                }
            }
            return names;
        } catch (Exception e) {
            this.logger().warning("FMMBridge: Could not read animation names from " + animationFile + ": " + e.getMessage());
            return List.of();
        }
    }
}
