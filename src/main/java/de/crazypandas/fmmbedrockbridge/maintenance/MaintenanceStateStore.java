package de.crazypandas.fmmbedrockbridge.maintenance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class MaintenanceStateStore {

    private static final String FILE_NAME = "maintenance-state.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MaintenanceStateStore() {}

    public static MaintenanceState load(Path dataFolder) {
        Path file = dataFolder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return null;
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return new MaintenanceState(
                    root.get("deployedHash").getAsString(),
                    Instant.parse(root.get("deployedAt").getAsString()),
                    root.get("deployedEmVersion").getAsString(),
                    Instant.parse(root.get("lastChecked").getAsString())
            );
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Path dataFolder, MaintenanceState state) {
        try {
            Files.createDirectories(dataFolder);
            JsonObject root = new JsonObject();
            root.addProperty("deployedHash", state.deployedHash());
            root.addProperty("deployedAt", state.deployedAt().toString());
            root.addProperty("deployedEmVersion", state.deployedEmVersion());
            root.addProperty("lastChecked", state.lastChecked().toString());
            Files.writeString(dataFolder.resolve(FILE_NAME), GSON.toJson(root));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save maintenance state: " + e.getMessage(), e);
        }
    }
}
