package de.crazypandas.fmmbedrockbridge.converter;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.ParsedTexture;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * Converts FMM models (.bbmodel) to Bedrock geometry + texture files
 * compatible with the GeyserUtils skin system.
 *
 * Handles multi-texture models by creating a texture atlas
 * (textures stacked vertically) and adjusting UV coordinates.
 *
 * Output per model:
 *   <output-path>/<modelId>/geometry.json
 *   <output-path>/<modelId>/texture.png
 */
public class BedrockModelConverter {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private static final Gson GSON = new Gson();

    private final File outputBase;
    private final double modelScale;

    public BedrockModelConverter() {
        String configPath = FMMBedrockBridge.getInstance().getConfig().getString("converter.output-path", "bedrock-skins");
        this.outputBase = new File(FMMBedrockBridge.getInstance().getDataFolder(), configPath);
        this.modelScale = FMMBedrockBridge.getInstance().getConfig().getDouble("converter.model-scale", 1.6);
    }

    /**
     * Convert all loaded FMM models.
     */
    public void convertAll(Map<String, FileModelConverter> models) {
        if (models.isEmpty()) {
            log.warning("[Converter] No FMM models found.");
            return;
        }
        log.info("[Converter] Converting " + models.size() + " FMM model(s)...");
        for (String modelId : models.keySet()) {
            try {
                convert(modelId, models);
            } catch (Exception e) {
                log.severe("[Converter] Failed to convert model '" + modelId + "': " + e.getMessage());
            }
        }
        try {
            Path packDir = FMMBedrockBridge.getInstance().getDataFolder().toPath().resolve("bedrock-pack");
            BedrockResourcePackGenerator.zip(packDir);
            log.info("[Converter] Resource pack zipped.");
        } catch (Exception e) {
            log.severe("[Converter] Failed to zip resource pack: " + e.getMessage());
        }
        log.info("[Converter] Done. Output: " + outputBase.getAbsolutePath());
    }

    /**
     * Convert a single FMM model by ID.
     */
    public void convert(String modelId, Map<String, FileModelConverter> models) throws IOException {
        FileModelConverter fmm = models.get(modelId);
        if (fmm == null) {
            log.warning("[Converter] Model not found: " + modelId);
            return;
        }

        File sourceFile = fmm.getSourceFile();
        if (sourceFile == null || !sourceFile.exists()) {
            log.warning("[Converter] Source file not found for model: " + modelId);
            return;
        }

        // Parse raw .bbmodel JSON
        Map<?, ?> bbmodel;
        try (Reader reader = new FileReader(sourceFile)) {
            bbmodel = GSON.fromJson(reader, Map.class);
        }

        // Get resolution from .bbmodel (UV coordinate space)
        Map<?, ?> resolution = (Map<?, ?>) bbmodel.get("resolution");
        double texWidth = 64;
        double texHeight = 64;
        if (resolution != null) {
            texWidth = toDouble(resolution.get("width"));
            texHeight = toDouble(resolution.get("height"));
        }

        // Get texture list from .bbmodel
        List<?> bbTextures = (List<?>) bbmodel.get("textures");
        int textureCount = bbTextures != null ? bbTextures.size() : 1;

        // Get texture dimensions from FMM
        List<ParsedTexture> parsedTextures = fmm.getParsedTextures();
        if (parsedTextures.isEmpty()) {
            log.warning("[Converter] No textures found for model: " + modelId);
            return;
        }

        // Create output folder
        File outDir = new File(outputBase, modelId);
        outDir.mkdirs();

        // Determine native texture dimensions for atlas quality
        int atlasSlotWidth = (int) texWidth;
        int atlasSlotHeight = (int) texHeight;
        if (textureCount > 1) {
            int[] nativeDims = findMaxNativeTextureDimensions(modelId, parsedTextures, bbTextures, (int) texWidth, (int) texHeight);
            atlasSlotWidth = nativeDims[0];
            atlasSlotHeight = nativeDims[1];
        }

        // Generate and write geometry.json (with atlas-aware UV offsets)
        String geoJson = BedrockGeometryGenerator.generate(modelId, bbmodel, texWidth, texHeight, textureCount,
                atlasSlotWidth, atlasSlotHeight);
        File geoFile = new File(outDir, "geometry.json");
        Files.writeString(geoFile.toPath(), geoJson);

        // Build texture atlas: stack all textures vertically
        File atlasFile = new File(outDir, "texture.png");
        if (textureCount > 1) {
            buildTextureAtlas(modelId, parsedTextures, bbTextures, atlasFile, atlasSlotWidth, atlasSlotHeight);
        } else {
            // Single texture: just copy it
            File textureFile = getFmmTextureFile(modelId, parsedTextures.get(0).getFilename());
            if (textureFile != null && textureFile.exists()) {
                Files.copy(textureFile.toPath(), atlasFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.warning("[Converter] Texture file not found for model: " + modelId);
            }
        }

        Path skinsDir = outDir.toPath();
        Path packDir = FMMBedrockBridge.getInstance().getDataFolder().toPath().resolve("bedrock-pack");
        BedrockResourcePackGenerator.generate(modelId, skinsDir, packDir, modelScale);

        // Write model-config.json so the Geyser extension can read the scale
        writeModelConfig(outDir);

        log.info("[Converter] Converted: " + modelId
                + " (textures=" + textureCount
                + ", uvSpace=" + (int) texWidth + "x" + (int) texHeight
                + ", atlas=" + atlasSlotWidth + "x" + (atlasSlotHeight * textureCount) + ")");
    }

    /**
     * Finds the maximum native texture dimensions across all textures.
     * Used to build the atlas at native resolution instead of UV resolution.
     */
    private int[] findMaxNativeTextureDimensions(String modelId, List<ParsedTexture> parsedTextures,
                                                  List<?> bbTextures, int fallbackWidth, int fallbackHeight) {
        int maxW = fallbackWidth;
        int maxH = fallbackHeight;
        for (int i = 0; i < parsedTextures.size(); i++) {
            File texFile = findTextureFile(modelId, parsedTextures.get(i).getFilename(), bbTextures, i);
            if (texFile != null && texFile.exists()) {
                try {
                    BufferedImage img = ImageIO.read(texFile);
                    maxW = Math.max(maxW, img.getWidth());
                    maxH = Math.max(maxH, img.getHeight());
                } catch (IOException ignored) {}
            }
        }
        return new int[]{maxW, maxH};
    }

    /**
     * Builds a texture atlas by stacking all textures vertically.
     * Each texture slot uses the specified dimensions (native resolution).
     */
    private void buildTextureAtlas(String modelId, List<ParsedTexture> parsedTextures,
                                    List<?> bbTextures, File outputFile,
                                    int slotWidth, int slotHeight) throws IOException {
        int totalHeight = slotHeight * parsedTextures.size();
        BufferedImage atlas = new BufferedImage(slotWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        for (int i = 0; i < parsedTextures.size(); i++) {
            File texFile = findTextureFile(modelId, parsedTextures.get(i).getFilename(), bbTextures, i);

            if (texFile != null && texFile.exists()) {
                BufferedImage texImage = ImageIO.read(texFile);
                g.drawImage(texImage, 0, i * slotHeight, slotWidth, slotHeight, null);
                log.info("[Converter] Atlas slot " + i + ": " + texFile.getName()
                        + " (" + texImage.getWidth() + "x" + texImage.getHeight()
                        + " → " + slotWidth + "x" + slotHeight + ")");
            } else {
                log.warning("[Converter] Texture " + i + " not found for model " + modelId
                        + " (filename: " + parsedTextures.get(i).getFilename() + ")");
                g.setColor(Color.MAGENTA);
                g.fillRect(0, i * slotHeight, slotWidth, slotHeight);
            }
        }

        g.dispose();
        ImageIO.write(atlas, "PNG", outputFile);
        log.info("[Converter] Texture atlas: " + slotWidth + "x" + totalHeight
                + " (" + parsedTextures.size() + " textures)");
    }

    private File findTextureFile(String modelId, String filename, List<?> bbTextures, int index) {
        File texFile = getFmmTextureFile(modelId, filename);
        if ((texFile == null || !texFile.exists()) && bbTextures != null && index < bbTextures.size()) {
            Map<?, ?> bbTex = (Map<?, ?>) bbTextures.get(index);
            String name = (String) bbTex.get("name");
            if (name != null) texFile = getFmmTextureFile(modelId, name);
        }
        return texFile;
    }

    /**
     * Finds the texture PNG that FMM wrote during its own startup.
     */
    private File getFmmTextureFile(String modelId, String filename) {
        Plugin fmmPlugin = Bukkit.getPluginManager().getPlugin("FreeMinecraftModels");
        if (fmmPlugin == null) return null;
        return new File(fmmPlugin.getDataFolder(),
                "output" + File.separator +
                "FreeMinecraftModels" + File.separator +
                "assets" + File.separator +
                "freeminecraftmodels" + File.separator +
                "textures" + File.separator +
                "entity" + File.separator +
                modelId + File.separator +
                filename);
    }

    private void writeModelConfig(File outDir) throws IOException {
        Map<String, Object> config = Map.of("model_scale", modelScale);
        Files.writeString(new File(outDir, "model-config.json").toPath(), GSON.toJson(config));
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0;
    }
}
