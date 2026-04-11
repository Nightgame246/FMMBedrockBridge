package de.crazypandas.fmmbridgeextension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FMMBridgeExtension implements Extension {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final java.util.List<String> registeredModels = new java.util.ArrayList<>();

    // Track whether we've registered the downstream listener for each session.
    private final Map<GeyserConnection, Long> lastRegistrationTime = new ConcurrentHashMap<>();
    private ScheduledExecutorService downstreamMonitor;
    private Method registerPacketListenerMethod;
    private Object geyserUtilsInstance;

    // Direct access to GeyserUtils.CUSTOM_ENTITIES for direct cache population (bypasses plugin messaging)
    @SuppressWarnings("rawtypes")
    private Map customEntitiesMap;  // Map<GeyserConnection, Cache<Integer, String>>
    @SuppressWarnings("rawtypes")
    private Map loadedEntityDefinitions;  // Map<String, EntityDefinition<?>>

    // Method to put entries directly into the Guava cache
    private Method cachePutMethod;  // Cache.put(key, value)

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

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        // Resolve GeyserUtils instance and methods for downstream re-registration
        initGeyserUtilsReflection();

        if (geyserUtilsInstance != null) {
            downstreamMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FMMBridge-DownstreamMonitor");
                t.setDaemon(true);
                return t;
            });
            downstreamMonitor.scheduleAtFixedRate(this::checkAllDownstreams, 2, 5, TimeUnit.SECONDS);
            this.logger().info("FMMBridge: Downstream monitor started (every 5s)");
        }
    }

    @Subscribe
    public void onSessionLogin(SessionLoginEvent event) {
        GeyserConnection connection = event.connection();
        this.logger().info("FMMBridge: Session login detected for " + connection);
        // Will be picked up by the downstream monitor
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        lastRegistrationTime.remove(event.connection());
    }

    private void initGeyserUtilsReflection() {
        try {
            // Find GeyserUtils extension instance
            for (Extension ext : GeyserApi.api().extensionManager().extensions()) {
                if (ext.getClass().getName().equals("me.zimzaza4.geyserutils.geyser.GeyserUtils")) {
                    geyserUtilsInstance = ext;
                    this.logger().info("FMMBridge: Found GeyserUtils extension instance: " + ext.getClass().getName());
                    break;
                }
            }

            if (geyserUtilsInstance == null) {
                this.logger().warning("FMMBridge: GeyserUtils extension not found — downstream fix disabled");
                return;
            }

            // Resolve registerPacketListener method
            for (Method m : geyserUtilsInstance.getClass().getDeclaredMethods()) {
                if (m.getName().equals("registerPacketListener") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    registerPacketListenerMethod = m;
                    this.logger().info("FMMBridge: Found registerPacketListener method: " + m);
                    break;
                }
            }

            if (registerPacketListenerMethod == null) {
                this.logger().warning("FMMBridge: registerPacketListener method not found on GeyserUtils");
            }

            // Access CUSTOM_ENTITIES and LOADED_ENTITY_DEFINITIONS for direct cache manipulation
            try {
                Field ceField = geyserUtilsInstance.getClass().getDeclaredField("CUSTOM_ENTITIES");
                ceField.setAccessible(true);
                customEntitiesMap = (Map) ceField.get(null);
                this.logger().info("FMMBridge: Accessed CUSTOM_ENTITIES map (size=" + customEntitiesMap.size() + ")");
            } catch (Exception e) {
                this.logger().warning("FMMBridge: Could not access CUSTOM_ENTITIES: " + e.getMessage());
            }

            try {
                Field ledField = geyserUtilsInstance.getClass().getDeclaredField("LOADED_ENTITY_DEFINITIONS");
                ledField.setAccessible(true);
                loadedEntityDefinitions = (Map) ledField.get(null);
                this.logger().info("FMMBridge: Accessed LOADED_ENTITY_DEFINITIONS (size=" + loadedEntityDefinitions.size() + ")");
            } catch (Exception e) {
                this.logger().warning("FMMBridge: Could not access LOADED_ENTITY_DEFINITIONS: " + e.getMessage());
            }
        } catch (Exception e) {
            this.logger().error("FMMBridge: Failed to initialize GeyserUtils reflection: " + e.getMessage(), e);
        }
    }

    private void checkAllDownstreams() {
        if (registerPacketListenerMethod == null) return;

        for (GeyserConnection connection : GeyserApi.api().onlineConnections()) {
            try {
                checkDownstream(connection);
            } catch (Exception e) {
                this.logger().error("FMMBridge: Error checking downstream for " + connection + ": " + e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void logCacheState(GeyserConnection connection) {
        if (customEntitiesMap == null) return;
        Object cache = customEntitiesMap.get(connection);
        if (cache == null) {
            this.logger().info("FMMBridge: CUSTOM_ENTITIES cache = null (no cache created for this session)");
            return;
        }
        try {
            // Cache is a Guava Cache<Integer, String> — use reflection with setAccessible
            Method asMapMethod = cache.getClass().getMethod("asMap");
            asMapMethod.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) asMapMethod.invoke(cache);
            if (!map.isEmpty()) {
                this.logger().info("FMMBridge: CUSTOM_ENTITIES has " + map.size() + " entries: " + map);
            } else {
                // Only log "empty" once every 10 seconds to avoid spam
                long now = System.currentTimeMillis();
                Long lastLog = lastRegistrationTime.get(connection);
                if (lastLog == null || (now - lastLog) < 2000) {
                    this.logger().info("FMMBridge: CUSTOM_ENTITIES cache is EMPTY for this session");
                }
            }
        } catch (Exception e) {
            // Try interface method instead
            try {
                // Guava Cache interface has size() method
                Method sizeMethod = findMethodAccessible(cache, "size");
                long size = (Long) sizeMethod.invoke(cache);
                this.logger().info("FMMBridge: CUSTOM_ENTITIES cache size=" + size);
            } catch (Exception e2) {
                this.logger().warning("FMMBridge: Could not read cache: " + e.getMessage()
                        + " (also tried size(): " + e2.getMessage() + ")");
            }
        }
    }

    private Method findMethodAccessible(Object obj, String methodName) throws NoSuchMethodException {
        // Search through class hierarchy for the method
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            // Try interfaces
            for (Class<?> iface : clazz.getInterfaces()) {
                try {
                    Method m = iface.getDeclaredMethod(methodName);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        throw new NoSuchMethodException(methodName);
    }

    private void checkDownstream(GeyserConnection connection) throws Exception {
        long now = System.currentTimeMillis();
        Long lastTime = lastRegistrationTime.get(connection);

        // Rate limit: register at most once every 30 seconds per connection.
        // First call (lastTime == null) always registers immediately.
        if (lastTime != null && (now - lastTime) < 30_000) {
            return;
        }

        lastRegistrationTime.put(connection, now);

        if (lastTime == null) {
            this.logger().info("FMMBridge: Initial connection — registering GeyserUtils downstream listener");
        }

        registerPacketListenerMethod.invoke(geyserUtilsInstance, connection);

        sendChannelRegistration(connection);
    }

    /**
     * Sends a minecraft:register packet through the downstream to register
     * the geyserutils:main channel with the backend server.
     * Without this, Velocity drops plugin messages on unregistered channels.
     */
    private void sendChannelRegistration(GeyserConnection connection) {
        try {
            // Access the downstream session: GeyserSession.getDownstream().getSession()
            Method getDownstream = connection.getClass().getMethod("getDownstream");
            getDownstream.setAccessible(true);
            Object downstream = getDownstream.invoke(connection);
            if (downstream == null) {
                this.logger().warning("FMMBridge: downstream is null, can't send channel registration");
                return;
            }

            Method getSession = downstream.getClass().getMethod("getSession");
            getSession.setAccessible(true);
            Object tcpSession = getSession.invoke(downstream);
            if (tcpSession == null) {
                this.logger().warning("FMMBridge: downstream TCP session is null");
                return;
            }

            // Construct a ServerboundCustomPayloadPacket for minecraft:register
            // containing "geyserutils:main" as the channel name
            byte[] channelBytes = "geyserutils:main".getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Find the ServerboundCustomPayloadPacket class
            Class<?> payloadClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket",
                    true, connection.getClass().getClassLoader());

            // Constructor: ServerboundCustomPayloadPacket(String channel, byte[] data)
            // or it might use Key for the channel
            Object packet = null;

            // Try different constructor signatures
            for (var constructor : payloadClass.getConstructors()) {
                var params = constructor.getParameterTypes();
                if (params.length == 2) {
                    if (params[0] == String.class && params[1] == byte[].class) {
                        packet = constructor.newInstance("minecraft:register", channelBytes);
                        break;
                    }
                    // MCProtocolLib might use Key type for channel
                    if (params[0].getSimpleName().contains("Key") && params[1] == byte[].class) {
                        // Create a Key from string
                        Method ofMethod = params[0].getMethod("key", String.class);
                        Object key = ofMethod.invoke(null, "minecraft:register");
                        packet = constructor.newInstance(key, channelBytes);
                        break;
                    }
                }
            }

            if (packet == null) {
                // Log available constructors for debugging
                this.logger().warning("FMMBridge: Could not find suitable constructor for ServerboundCustomPayloadPacket");
                for (var c : payloadClass.getConstructors()) {
                    this.logger().warning("  -> " + c);
                }
                return;
            }

            // Send the packet through the downstream session
            Method sendMethod = tcpSession.getClass().getMethod("send",
                    Class.forName("org.geysermc.mcprotocollib.network.packet.Packet", true, connection.getClass().getClassLoader()));
            sendMethod.setAccessible(true);
            sendMethod.invoke(tcpSession, packet);

            // Channel registration sent successfully
        } catch (Exception e) {
            this.logger().warning("FMMBridge: Failed to send channel registration: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void disable() {
        if (downstreamMonitor != null) {
            downstreamMonitor.shutdownNow();
            downstreamMonitor = null;
        }
        lastRegistrationTime.clear();
        Extension.super.disable();
    }

    private void processModelDirectory(Path modelDir, Path packDir) {
        String modelId = modelDir.getFileName().toString().toLowerCase(Locale.ROOT);
        Path geometryPath = modelDir.resolve("geometry.json");
        Path texturePath = modelDir.resolve("texture.png");

        if (!Files.exists(geometryPath) || !Files.exists(texturePath)) {
            this.logger().info("Skipping " + modelId + " because geometry.json or texture.png is missing.");
            return;
        }

        // Read model scale from config file (written by Spigot-side converter)
        double modelScale = readModelScale(modelDir);

        try {
            if (registerCustomEntityWithDebug(modelId)) {
                registeredModels.add(modelId);
                this.logger().info("FMMBridgeExtension: Registered model " + modelId + " for Bedrock (scale=" + modelScale + ")");
            } else {
                this.logger().warning("FMMBridgeExtension: Failed to register model " + modelId);
                return;
            }

            // Register animation properties BEFORE generatePackFiles
            // Must happen during GeyserPreInitializeEvent while GEYSER_LOADED=false
            // to avoid the recursive bug in GeyserUtils.registerProperties()
            Path animPath = modelDir.resolve("animations.json");
            if (Files.exists(animPath)) {
                List<String> animNames = readAnimationNames(animPath);
                registerAnimationProperties(modelId, animNames.size());
            }

            generatePackFiles(modelId, geometryPath, texturePath, packDir, modelScale);
            this.logger().info("Prepared Bedrock assets for " + modelId);
        } catch (Exception e) {
            this.logger().error("Failed to prepare model " + modelId + ": " + e.getMessage(), e);
        }
    }

    private double readModelScale(Path modelDir) {
        Path configPath = modelDir.resolve("model-config.json");
        if (!Files.exists(configPath)) return 1.6; // default FMM scale
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

    private boolean registerCustomEntityWithDebug(String modelId) {
        String fullId = "fmmbridge:" + modelId;
        this.logger().info("FMMBridgeExtension: Attempting to register custom entity: " + fullId);

        // Try multiple classloader strategies
        ClassLoader[] classLoaders = {
            this.getClass().getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        };

        for (ClassLoader cl : classLoaders) {
            if (cl == null) continue;

            try {
                this.logger().info("FMMBridgeExtension: Trying classloader: " + cl);
                Class<?> geyserUtilsClass = Class.forName("me.zimzaza4.geyserutils.geyser.GeyserUtils", true, cl);
                this.logger().info("FMMBridgeExtension: GeyserUtils class found via " + cl);

                java.lang.reflect.Method addCustomEntity = geyserUtilsClass.getMethod("addCustomEntity", String.class);
                this.logger().info("FMMBridgeExtension: addCustomEntity method found");

                this.logger().info("FMMBridgeExtension: Calling invoke()...");
                addCustomEntity.invoke(null, fullId);
                this.logger().info("FMMBridgeExtension: invoke() successful for " + fullId);
                return true;

            } catch (ClassNotFoundException e) {
                this.logger().info("FMMBridgeExtension: Class not found with classloader " + cl + ": " + e.getMessage());
            } catch (NoSuchMethodException e) {
                this.logger().warning("FMMBridgeExtension: addCustomEntity method not found via " + cl);
                this.logger().error("NoSuchMethodException details", e);
            } catch (Exception e) {
                this.logger().warning("FMMBridgeExtension: Failed via " + cl + ": " + e.getClass().getName());
                this.logger().error("Full exception details", e);
            }
        }

        this.logger().warning("FMMBridgeExtension: All classloader strategies failed for " + fullId);
        return false;
    }

    /**
     * Registers animation properties for a model with GeyserUtils.
     * Must be called AFTER addCustomEntity() and BEFORE Geyser finishes loading.
     * Pattern from GeyserModelEngine Entity.java:128-134.
     */
    private void registerAnimationProperties(String modelId, int animationCount) {
        if (animationCount == 0) return;

        String fullId = "fmmbridge:" + modelId;
        int slotCount = (int) Math.ceil(animationCount / 24.0);

        ClassLoader cl = this.getClass().getClassLoader();
        try {
            Class<?> geyserUtilsClass = Class.forName(
                    "me.zimzaza4.geyserutils.geyser.GeyserUtils", true, cl);

            Method addProperty = geyserUtilsClass.getMethod(
                    "addProperty", String.class, String.class, Class.class);
            Method registerProps = geyserUtilsClass.getMethod(
                    "registerProperties", String.class);

            for (int i = 0; i < slotCount; i++) {
                addProperty.invoke(null, fullId, "fmmbridge:anim" + i, Integer.class);
            }
            registerProps.invoke(null, fullId);

            this.logger().info("FMMBridge: Registered " + slotCount
                    + " animation property slots for " + fullId);
        } catch (Exception e) {
            this.logger().warning("FMMBridge: Failed to register animation properties for "
                    + fullId + ": " + e.getMessage());
        }
    }

    @Deprecated
    private void registerCustomEntity(String modelId) throws ReflectiveOperationException {
        Class.forName("me.zimzaza4.geyserutils.geyser.GeyserUtils")
                .getMethod("addCustomEntity", String.class)
                .invoke(null, "fmmbridge:" + modelId);
    }

    private void generatePackFiles(String modelId, Path geometryPath, Path texturePath, Path packDir, double modelScale) throws IOException {
        // Find model directory (parent of geometry.json)
        Path modelDir = geometryPath.getParent();

        // Check for animation files
        Path animPath = modelDir.resolve("animations.json");
        Path controllerPath = modelDir.resolve("animation_controllers.json");
        boolean hasAnimations = Files.exists(animPath) && Files.exists(controllerPath);
        List<String> animationNames = hasAnimations ? readAnimationNames(animPath) : List.of();

        writeManifest(packDir.resolve("manifest.json"));
        writeJson(packDir.resolve("entity").resolve(modelId + ".json"),
                createEntityDefinition(modelId, modelScale, animationNames));
        writeJson(packDir.resolve("render_controllers").resolve(modelId + ".json"), createRenderController(modelId));

        Files.createDirectories(packDir.resolve("models").resolve("entity"));
        Files.copy(geometryPath,
                packDir.resolve("models").resolve("entity").resolve(modelId + ".geo.json"),
                StandardCopyOption.REPLACE_EXISTING);

        Files.createDirectories(packDir.resolve("textures").resolve("entity"));
        Files.copy(texturePath,
                packDir.resolve("textures").resolve("entity").resolve(modelId + ".png"),
                StandardCopyOption.REPLACE_EXISTING);

        // Copy animation files if present
        if (hasAnimations) {
            Files.createDirectories(packDir.resolve("animations"));
            Files.copy(animPath,
                    packDir.resolve("animations").resolve(modelId + ".animation.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(packDir.resolve("animation_controllers"));
            Files.copy(controllerPath,
                    packDir.resolve("animation_controllers").resolve(modelId + ".animation_controllers.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            this.logger().info("FMMBridgeExtension: " + modelId + " has " + animationNames.size() + " animations");
        }
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

    private Map<String, Object> createEntityDefinition(String modelId, double modelScale, List<String> animationNames) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", "fmmbridge:" + modelId);
        description.put("materials", Map.of("default", "entity_alphatest_change_color_one_sided"));
        description.put("textures", Map.of("default", "textures/entity/" + modelId));
        description.put("geometry", Map.of("default", "geometry.fmmbridge." + modelId));
        description.put("render_controllers", List.of("controller.render.fmmbridge_" + modelId));
        description.put("spawn_egg", Map.of("base_color", "#000000", "overlay_color", "#FFFFFF"));

        // Animation references
        if (!animationNames.isEmpty()) {
            Map<String, String> animations = new LinkedHashMap<>();
            java.util.List<String> controllerRefs = new java.util.ArrayList<>();
            for (String animName : animationNames) {
                animations.put(animName, "animation.fmmbridge." + modelId + "." + animName);
                controllerRefs.add("controller.animation.fmmbridge." + modelId + "." + animName);
            }
            description.put("animations", animations);
            description.put("animation_controllers", controllerRefs);
        }

        // Scale to match FMM's Java-side visual size
        Map<String, Object> scripts = new LinkedHashMap<>();
        if (modelScale != 1.0) {
            scripts.put("scale", String.valueOf(modelScale));
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

    private List<String> readAnimationNames(Path animationFile) {
        try {
            String json = Files.readString(animationFile);
            Map<?, ?> parsed = GSON.fromJson(json, Map.class);
            Map<?, ?> animations = (Map<?, ?>) parsed.get("animations");
            if (animations == null) return List.of();

            java.util.List<String> names = new java.util.ArrayList<>();
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
            this.logger().warning("FMMBridge: Could not read animation names from " + animationFile + ": " + e.getMessage());
            return List.of();
        }
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
