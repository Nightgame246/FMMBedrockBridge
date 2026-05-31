package de.crazypandas.fmmbedrockbridge.maintenance;

import java.nio.file.Path;
import java.time.Instant;

public final class MaintenanceTracker {

    private final Path dataFolder;

    public MaintenanceTracker(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    public record Result(
            boolean firstRun,
            boolean driftActive,
            String currentHash,
            String deployedHash,
            String deployedEmVersion,
            Instant deployedAt
    ) {}

    public Result evaluate(String currentHash, String currentEmVersion) {
        Instant now = Instant.now();
        MaintenanceState state = MaintenanceStateStore.load(dataFolder);

        if (state == null) {
            MaintenanceState fresh = new MaintenanceState(currentHash, now, currentEmVersion, now);
            MaintenanceStateStore.save(dataFolder, fresh);
            return new Result(true, false, currentHash, currentHash, currentEmVersion, now);
        }

        boolean drift = !currentHash.equals(state.deployedHash());

        MaintenanceState updated = new MaintenanceState(
                state.deployedHash(), state.deployedAt(), state.deployedEmVersion(), now);
        MaintenanceStateStore.save(dataFolder, updated);

        return new Result(false, drift, currentHash, state.deployedHash(),
                state.deployedEmVersion(), state.deployedAt());
    }

    public void markDeployed(String hash, String emVersion) {
        Instant now = Instant.now();
        MaintenanceStateStore.save(dataFolder,
                new MaintenanceState(hash, now, emVersion, now));
    }
}
