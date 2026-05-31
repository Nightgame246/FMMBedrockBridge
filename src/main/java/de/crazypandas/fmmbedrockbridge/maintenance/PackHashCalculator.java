package de.crazypandas.fmmbedrockbridge.maintenance;

import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Logger;

public final class PackHashCalculator {

    private PackHashCalculator() {}

    private static final Logger LOG = Logger.getLogger(PackHashCalculator.class.getName());

    public static String compute(List<EMCustomItem> items) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        items.stream()
                .sorted(Comparator
                        .comparing(EMCustomItem::javaMaterial)
                        .thenComparingInt(EMCustomItem::customModelData)
                        .thenComparing(EMCustomItem::bedrockTextureKey))
                .forEach(item -> {
                    md.update((item.javaMaterial() + "|" + item.customModelData()
                            + "|" + item.bedrockTextureKey() + "|").getBytes(StandardCharsets.UTF_8));
                    md.update(readPngBytes(item.sourceTexturePath()));
                    md.update((byte) '\n');
                });

        return HexFormat.of().formatHex(md.digest());
    }

    private static byte[] readPngBytes(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            LOG.warning("PackHashCalculator: cannot read PNG '" + path + "': " + e.getMessage());
            return new byte[0];
        }
    }
}
