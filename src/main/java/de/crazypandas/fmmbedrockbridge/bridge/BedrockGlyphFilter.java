package de.crazypandas.fmmbedrockbridge.bridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 7.5 — strips Java resource-pack font glyphs from text headed for a Bedrock client.
 *
 * <p><b>The problem.</b> EliteMobs 10.9.0 renders its combat HUD out of a custom font
 * ({@code assets/elitemobs/font/combat_hud_*.json}): bars are built from hundreds of tiny glyphs
 * that merge into a smooth image on Java. Bedrock cannot resolve a Java font provider, and the
 * codepoints EliteMobs uses — 689 of its 703 glyphs sit in the Unicode Private Use Area,
 * U+E000–U+F8FF — collide with Bedrock's own symbol sheet. Each bar segment is drawn as a full
 * item icon instead. In-game this produced several hundred armour and carrot icons covering half
 * the screen, re-sent on the action-bar compositor's keepalive loop, which lagged the client out
 * badly enough that the player could not be tested at all.
 *
 * <p><b>Why the Private Use Area is the right thing to key on.</b> By definition it carries no
 * standard meaning — its interpretation depends entirely on the font in use. A Bedrock client has
 * no way to render those codepoints as intended, so dropping them loses nothing that could have
 * been displayed correctly. What remains is the plain text, which Bedrock renders fine.
 *
 * <p>This deliberately does not target EliteMobs specifically: any plugin drawing with a Java
 * font hits the same wall on Bedrock.
 */
public final class BedrockGlyphFilter {

    private static final Pattern VALUE_PAIR = Pattern.compile("[0-9]+\\s*/\\s*[0-9]+");
    private static final Pattern LEADING_COLOURS = Pattern.compile("^(§[0-9a-fk-orx])+");
    private static final Pattern PROSE = Pattern.compile(".*[A-Za-zÄÖÜäöüß]{3,}.*", Pattern.DOTALL);

    private static final char PUA_FIRST = '';
    private static final char PUA_LAST = '';

    private BedrockGlyphFilter() {
    }

    /**
     * How many Private Use Area glyphs the text holds.
     *
     * <p>Used to tell EliteMobs' HUD apart from its ordinary messages: the HUD draws bars out of
     * hundreds of glyphs, while a message like "Class controls are not active here" carries at
     * most a handful of decorative ones.
     */
    public static int countGlyphs(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isGlyph(text.charAt(i))) n++;
        }
        return n;
    }

    /** True when the text holds at least one Private Use Area glyph worth stripping. */
    public static boolean containsGlyphs(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (isGlyph(text.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Removes Private Use Area glyphs and tidies up the whitespace they leave behind.
     *
     * <p>A HUD bar is one long run of glyphs; deleting them character by character would leave a
     * storm of blanks, so runs are collapsed and the result is trimmed.
     *
     * @return the cleaned text, or the input unchanged when there is nothing to strip
     *         ({@code null} in, {@code null} out)
     */
    public static String strip(String text) {
        if (!containsGlyphs(text)) return text;

        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // A glyph run separated two readable parts — "56/60" and "92/100" sit either side of
            // a bar with no space between them. Dropping the run outright glues them into
            // "56/6092/100", so a run becomes exactly one space.
            if (isGlyph(c) || c == ' ') {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /**
     * Splits EliteMobs' message off a stripped HUD line, or {@code null} when there is none.
     *
     * <p>EliteMobs' {@code ActionBarCompositor} mixes sources into one packet: the HUD and a
     * message such as "Teleportiere in 3 Sekunden…" arrive together. The HUD's last element is
     * always a "current/maximum" pair, so everything after the final pair is the message.
     *
     * <p>It is returned <b>verbatim</b>. An earlier version scrubbed digits to tell prose from
     * numbers and handed back the scrubbed text — which turned "Not enough Stamina (20 required)"
     * into "Not enough Stamina (  required)".
     */
    public static String messageAfterHud(String stripped) {
        if (stripped == null || stripped.isEmpty()) return null;

        Matcher pair = VALUE_PAIR.matcher(stripped);
        int messageStart = -1;
        while (pair.find()) {
            messageStart = pair.end();
        }
        if (messageStart < 0) return null;

        String tail = stripped.substring(messageStart).trim();
        tail = LEADING_COLOURS.matcher(tail).replaceAll("").trim();
        if (tail.isEmpty()) return null;

        // Letters make it prose; a stray separator is not a message.
        return PROSE.matcher(tail).matches() ? tail : null;
    }

    /**
     * Drops a leading class name from a message, so the line does not read
     * "Adventurer … Adventurer [EM] Teleportiere …". EliteMobs prefixes its own messages with
     * the class name, which our HUD line already carries.
     */
    public static String withoutLeadingName(String message, String name) {
        if (message == null || name == null || name.isEmpty()) return message;
        String bare = message.replaceAll("^(§[0-9a-fk-orx])+", "");
        if (!bare.startsWith(name)) return message;
        // Only the separator EliteMobs puts between name and message goes — §f/§r plus spaces.
        // A colour that belongs to the message itself (its §7, say) has to survive.
        String rest = bare.substring(name.length()).replaceAll("^(§[fr]|\\s)+", "");
        return rest.isEmpty() ? null : rest;
    }

    private static boolean isGlyph(char c) {
        return c >= PUA_FIRST && c <= PUA_LAST;
    }
}
