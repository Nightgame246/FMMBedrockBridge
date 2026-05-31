package de.crazypandas.fmmbedrockbridge.maintenance;

import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PackHashCalculatorTest {

    @Test
    void emptyListProducesStableHash() {
        String h1 = PackHashCalculator.compute(List.of());
        String h2 = PackHashCalculator.compute(List.of());
        assertEquals(h1, h2);
        assertEquals(64, h1.length()); // SHA-256 hex
    }

    @Test
    void orderIndependent(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("a.png");
        Files.write(png, new byte[]{1, 2, 3});
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, png.toString(), "em_a");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 2, png.toString(), "em_b");

        String h1 = PackHashCalculator.compute(List.of(a, b));
        String h2 = PackHashCalculator.compute(List.of(b, a));
        assertEquals(h1, h2, "Hash must be order-independent (sort inside compute)");
    }

    @Test
    void cmdDifferenceChangesHash(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("a.png");
        Files.write(png, new byte[]{1, 2, 3});
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, png.toString(), "em_a");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 2, png.toString(), "em_a");

        assertNotEquals(PackHashCalculator.compute(List.of(a)),
                        PackHashCalculator.compute(List.of(b)));
    }

    @Test
    void pngBytesDifferenceChangesHash(@TempDir Path tmp) throws Exception {
        Path p1 = tmp.resolve("a.png");
        Path p2 = tmp.resolve("b.png");
        Files.write(p1, new byte[]{1, 2, 3});
        Files.write(p2, new byte[]{4, 5, 6});

        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, p1.toString(), "em_a");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 1, p2.toString(), "em_a");

        assertNotEquals(PackHashCalculator.compute(List.of(a)),
                        PackHashCalculator.compute(List.of(b)),
                "PNG content change must change hash even when key stays same");
    }

    @Test
    void missingPngFileTreatedAsEmptyBytes(@TempDir Path tmp) {
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, tmp.resolve("nope.png").toString(), "em_a");
        // Should not throw, just treat missing as empty bytes
        String h = PackHashCalculator.compute(List.of(a));
        assertEquals(64, h.length());
    }
}
