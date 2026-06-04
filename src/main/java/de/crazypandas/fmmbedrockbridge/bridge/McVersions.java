package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Pure helper for comparing a dotted Minecraft version string (e.g. "1.21.10")
 * against a major.minor.patch threshold. Anything after the first '-', ' ' or '_'
 * (e.g. "-R0.1-SNAPSHOT") is ignored. Malformed input returns false (fail-safe).
 */
public final class McVersions {

    private McVersions() {}

    public static boolean isAtLeast(String version, int major, int minor, int patch) {
        if (version == null) return false;
        int cut = version.length();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '-' || c == ' ' || c == '_') { cut = i; break; }
        }
        String[] parts = version.substring(0, cut).split("\\.");
        int[] v = new int[]{0, 0, 0};
        try {
            for (int i = 0; i < 3 && i < parts.length; i++) {
                v[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return false;
        }
        if (v[0] != major) return v[0] > major;
        if (v[1] != minor) return v[1] > minor;
        return v[2] >= patch;
    }
}
