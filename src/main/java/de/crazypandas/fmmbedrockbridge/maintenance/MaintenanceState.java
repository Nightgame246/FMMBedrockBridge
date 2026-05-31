package de.crazypandas.fmmbedrockbridge.maintenance;

import java.time.Instant;

public record MaintenanceState(
        String deployedHash,
        Instant deployedAt,
        String deployedEmVersion,
        Instant lastChecked
) {
}
