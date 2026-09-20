package de.crazypandas.fmmbedrockbridge.bridge;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Pure "is this a Bedrock player" decision, extracted so it is unit-testable without Bukkit,
 * Floodgate or Geyser on the classpath.
 *
 * <p>The order mirrors EliteMobs 10.9.5 ({@code BedrockChecker.isBedrock}) on purpose. Until
 * 10.9.4 both EliteMobs and this bridge asked Floodgate alone and returned its answer, so a
 * {@code false} never reached Geyser — that is the bug MagmaGuy fixed as <em>"improved Bedrock
 * player detection behind proxies"</em>. Where the two disagree, EliteMobs hands the player its
 * pack-free Bedrock fallback while the bridge would treat them as a Java player and withhold
 * ability input, which is exactly the lockout reported upstream on 11.09.2026.
 */
public final class BedrockDetection {

    /** Floodgate's username prefix plus the four digits it appends to disambiguate. */
    private static final Pattern BEDROCK_NAME_PATTERN = Pattern.compile("^\\..*\\d{4}$");

    private BedrockDetection() {}

    /**
     * @param uuid                 the player's UUID; Floodgate zeroes the most significant bits
     * @param name                 the player's name, may be {@code null}
     * @param floodgateSaysBedrock asks Floodgate, only called if the UUID and name were unclear
     * @param geyserSaysBedrock    asks Geyser, only called if Floodgate did not say yes
     */
    public static boolean isBedrock(UUID uuid, String name,
                                    BooleanSupplier floodgateSaysBedrock,
                                    BooleanSupplier geyserSaysBedrock) {
        if (uuid != null && uuid.getMostSignificantBits() == 0L) return true;
        if (name != null && BEDROCK_NAME_PATTERN.matcher(name).matches()) return true;
        if (ask(floodgateSaysBedrock)) return true;
        return ask(geyserSaysBedrock);
    }

    /**
     * A missing or half-initialised API must count as "not Bedrock", never as a crash — and must
     * not swallow the next source's answer either.
     */
    private static boolean ask(BooleanSupplier source) {
        try {
            return source != null && source.getAsBoolean();
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        }
    }
}
