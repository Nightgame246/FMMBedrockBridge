package de.crazypandas.fmmbedrockbridge.bridge;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 7.6 — rewrites EliteMobs' menu titles for Bedrock.
 *
 * <p>EliteMobs draws its menu backgrounds as font glyphs in the chest title, at code points
 * {@code U+F0E00}–{@code U+F0F0B}, i.e. outside the Basic Multilingual Plane. Bedrock shows those
 * as boxes. This replaces them by markers inside the BMP that the generated Bedrock pack
 * recognises ({@code ui/chest_screen.json}) and renders invisible (transparent glyph pages):
 * {@code U+E8FF} = "slot backgrounds off", {@code U+E900 + n} = "background n".
 *
 * <p>Pure on purpose — no Bukkit, no PacketEvents — so the rule is tested against the same cases
 * as the Python generator ({@code src/test/resources/bedrock-menus/cases.json}).
 */
public final class BedrockMenuTitle {

    public static final int EM_BLOCK_START = 0xF0E00;
    public static final int EM_BLOCK_END = 0xF0F0B;
    public static final int MARKER_BASE = 0xE900;
    public static final int GENERIC_MARKER = 0xE8FF;
    /** Negative-advance glyphs in EliteMobs' font: ascent -32768, negative height. */
    public static final Set<Integer> SPACING_CODEPOINTS = Set.of(0xF0EF1, 0xF0EF5);
    /**
     * The backgrounds the generated pack has an image for — written by
     * {@code tools/bedrock-menus/generate.py} into {@code bedrock-menus/known-backgrounds.txt}.
     * Only these get a marker (and the slot-hiding {@link #GENERIC_MARKER}); anything else from the
     * plane is stripped. Without the list, a new spacing glyph that EliteMobs puts before the
     * background would be taken for the background, and the grey cells would vanish with no image
     * behind them (final review 01.10.2026).
     */
    public static final Set<Integer> KNOWN_BACKGROUNDS = loadKnownBackgrounds();

    private BedrockMenuTitle() {}

    public static int markerFor(int emCodepoint) {
        if (emCodepoint < EM_BLOCK_START || emCodepoint > EM_BLOCK_END
                || SPACING_CODEPOINTS.contains(emCodepoint)) {
            throw new IllegalArgumentException("no menu background: U+" + Integer.toHexString(emCodepoint));
        }
        return MARKER_BASE + (emCodepoint - EM_BLOCK_START);
    }

    /**
     * @param legacyTitle title in legacy §-format
     * @param hideSlots   also emit {@link #GENERIC_MARKER} (Bedrock hides the grey slot cells)
     * @return the rewritten title, or {@code legacyTitle} itself when it holds no plane-15/16 character
     */
    public static String rewrite(String legacyTitle, boolean hideSlots) {
        return rewrite(legacyTitle, hideSlots, KNOWN_BACKGROUNDS);
    }

    /** @param knownBackgrounds EliteMobs code points the Bedrock pack has an image for */
    public static String rewrite(String legacyTitle, boolean hideSlots, Set<Integer> knownBackgrounds) {
        if (legacyTitle == null || !containsSupplementaryPua(legacyTitle)) return legacyTitle;

        StringBuilder out = new StringBuilder(legacyTitle.length());
        boolean markerWritten = false;
        boolean skipSpaces = false;
        int i = 0;
        while (i < legacyTitle.length()) {
            int cp = legacyTitle.codePointAt(i);
            i += Character.charCount(cp);

            if (isSupplementaryPua(cp)) {
                if (!markerWritten && isBackground(cp) && knownBackgrounds.contains(cp)) {
                    if (hideSlots) out.appendCodePoint(GENERIC_MARKER);
                    out.appendCodePoint(markerFor(cp));
                    markerWritten = true;
                }
                skipSpaces = true;   // spaces right after the prefix only pushed the name right on Java
                continue;
            }
            if (skipSpaces && cp == ' ') continue;
            skipSpaces = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static Set<Integer> loadKnownBackgrounds() {
        Set<Integer> known = new HashSet<>();
        try (InputStream in = BedrockMenuTitle.class.getResourceAsStream("/bedrock-menus/known-backgrounds.txt")) {
            if (in == null) return Set.of();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) known.add(Integer.parseInt(line, 16));
            }
        } catch (Exception e) {
            return Set.of();   // no list = no markers, titles only cleaned: safe, never boxes
        }
        return Set.copyOf(known);
    }

    private static boolean isBackground(int cp) {
        return cp >= EM_BLOCK_START && cp <= EM_BLOCK_END && !SPACING_CODEPOINTS.contains(cp);
    }

    private static boolean isSupplementaryPua(int cp) {
        return (cp >= 0xF0000 && cp <= 0xFFFFD) || (cp >= 0x100000 && cp <= 0x10FFFD);
    }

    private static boolean containsSupplementaryPua(String s) {
        return s.codePoints().anyMatch(BedrockMenuTitle::isSupplementaryPua);
    }
}
