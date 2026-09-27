package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection order mirrored from EliteMobs 10.9.5
 * ({@code magmaguy.easyminecraftgoals.thirdparty.BedrockChecker}).
 *
 * <p>Until 10.9.4 EliteMobs returned Floodgate's answer directly, so a {@code false} from
 * Floodgate never reached Geyser — the bug MagmaGuy fixed as "improved Bedrock player
 * detection behind proxies". Our bridge carried the same shape and is caught up here.
 */
class BedrockDetectionTest {

    /** Floodgate hands Bedrock players a UUID whose most significant bits are zero. */
    private static final UUID FLOODGATE_UUID = new UUID(0L, 0x0009_1234_5678L);
    private static final UUID JAVA_UUID = UUID.fromString("f7c77d99-9f15-4a66-a87d-c4a51ef30d19");

    private static BooleanSupplier says(boolean value) {
        return () -> value;
    }

    private static BooleanSupplier explodes() {
        return () -> {
            throw new NoClassDefFoundError("org/geysermc/floodgate/api/FloodgateApi");
        };
    }

    @Test
    void floodgateUuidIsBedrockWithoutAskingAnyApi() {
        AtomicInteger calls = new AtomicInteger();
        BooleanSupplier counting = () -> {
            calls.incrementAndGet();
            return false;
        };

        assertTrue(BedrockDetection.isBedrock(FLOODGATE_UUID, "Steve", counting, counting));
        assertEquals(0, calls.get(), "UUID answer must settle it before any API is touched");
    }

    @Test
    void floodgatePrefixedNameIsBedrock() {
        // A Java username can never start with '.', so this cannot hit a Java player.
        assertTrue(BedrockDetection.isBedrock(JAVA_UUID, ".Gamer1234", says(false), says(false)));
    }

    @Test
    void plainJavaNameIsNotBedrockByItself() {
        assertFalse(BedrockDetection.isBedrock(JAVA_UUID, "Gamer1234", says(false), says(false)));
    }

    @Test
    void floodgateSayingNoStillAsksGeyser() {
        // The "behind proxies" case: Geyser runs on the proxy, Floodgate answers no here.
        assertTrue(BedrockDetection.isBedrock(JAVA_UUID, "Steve", says(false), says(true)));
    }

    @Test
    void floodgateSayingYesIsEnough() {
        assertTrue(BedrockDetection.isBedrock(JAVA_UUID, "Steve", says(true), says(false)));
    }

    @Test
    void geyserIsNotAskedOnceFloodgateSaidYes() {
        AtomicInteger geyserCalls = new AtomicInteger();
        BooleanSupplier geyser = () -> {
            geyserCalls.incrementAndGet();
            return false;
        };

        assertTrue(BedrockDetection.isBedrock(JAVA_UUID, "Steve", says(true), geyser));
        assertEquals(0, geyserCalls.get());
    }

    @Test
    void javaPlayerIsNotBedrock() {
        assertFalse(BedrockDetection.isBedrock(JAVA_UUID, "Steve", says(false), says(false)));
    }

    @Test
    void aMissingApiCountsAsNoAndDoesNotEscape() {
        // Floodgate class absent → LinkageError instead of an answer. Must not reach the caller,
        // and must not swallow the Geyser answer either.
        assertTrue(BedrockDetection.isBedrock(JAVA_UUID, "Steve", explodes(), says(true)));
        assertFalse(BedrockDetection.isBedrock(JAVA_UUID, "Steve", explodes(), explodes()));
    }

    @Test
    void runtimeExceptionFromAnApiCountsAsNo() {
        BooleanSupplier throwing = () -> {
            throw new IllegalStateException("Floodgate not initialised yet");
        };
        assertFalse(BedrockDetection.isBedrock(JAVA_UUID, "Steve", throwing, says(false)));
    }

    @Test
    void missingNameIsTolerated() {
        assertFalse(BedrockDetection.isBedrock(JAVA_UUID, null, says(false), says(false)));
        assertTrue(BedrockDetection.isBedrock(FLOODGATE_UUID, null, says(false), says(false)));
    }
}
