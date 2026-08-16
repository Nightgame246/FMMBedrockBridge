package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Pure helper for comparing a dotted Minecraft version string (e.g. "1.21.10")
 * against a major.minor.patch threshold. Anything after the first '-', ' ' or '_'
 * (e.g. "-R0.1-SNAPSHOT") is ignored. Malformed input returns false (fail-safe).
 *
 * <p>Handles both Minecraft version schemes: the classic "1.21.10" and the
 * year-based one Mojang moved to in 2026 ("26.1" = first drop of 2026, "26.1.2"
 * = its second hotfix). There is no 1.22 — 26.x simply compares greater than any
 * 1.x, which is exactly what the numeric comparison below yields.
 *
 * <p>Non-numeric trailing segments are ignored rather than treated as malformed,
 * because build/channel suffixes do occur in the wild ("26.2.build.87-stable").
 * A non-numeric <em>leading</em> segment is still malformed: we cannot order it,
 * so we fail closed.
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
        int parsed = 0;
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                v[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                break;   // trailing build/channel segment — everything numeric so far stands
            }
            parsed++;
        }
        if (parsed == 0) return false;   // nothing numeric at the front: cannot order it
        if (v[0] != major) return v[0] > major;
        if (v[1] != minor) return v[1] > minor;
        return v[2] >= patch;
    }
}
