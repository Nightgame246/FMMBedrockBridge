package de.crazypandas.fmmbedrockbridge.bridge;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Maps an opened inventory title to the dialog-invoker that should replace it for
 * Bedrock players. Titles are matched after {@link #normalize(String)} so config
 * color formatting (e.g. {@code §2EliteMobs Index}) does not break the match.
 *
 * <p>Each entry's title is supplied lazily (read from EM config at match time, so
 * it reflects the live configured/localized menu name). Phase 7.3 registers a
 * single entry (EM status index); the structure makes additional EM menus with a
 * dialog equivalent a one-line addition.
 */
public final class MenuRerouteRegistry {

    private record Entry(Supplier<String> titleSupplier, Consumer<Player> invoker) {}

    private final List<Entry> entries = new ArrayList<>();

    public void register(Supplier<String> titleSupplier, Consumer<Player> invoker) {
        entries.add(new Entry(titleSupplier, invoker));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The invoker whose configured title matches {@code openedTitle}, if any. */
    public Optional<Consumer<Player>> findInvoker(String openedTitle) {
        if (openedTitle == null) return Optional.empty();
        String norm = normalize(openedTitle);
        for (Entry e : entries) {
            String expected = e.titleSupplier().get();
            if (expected == null) continue;
            if (normalize(expected).equals(norm)) return Optional.of(e.invoker());
        }
        return Optional.empty();
    }

    /** Strip legacy color codes (&x / §x), trim, lowercase (Locale.ROOT). */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                i++; // skip the code char too
                continue;
            }
            b.append(c);
        }
        return b.toString().trim().toLowerCase(Locale.ROOT);
    }
}
