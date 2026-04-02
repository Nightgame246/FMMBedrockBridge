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

    public BedrockModelConverter() {
        String configPath = FMMBedrockBridge.getInstance().getConfig().getString("converter.output-path", "bedrock-skins");
        this.outputBase = new File(FMMBedrockBridge.getInstance().getDataFolder(), configPath);
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

        // Generate and write geometry.json (with atlas-aware UV offsets)
        String geoJson = BedrockGeometryGenerator.generate(modelId, bbmodel, texWidth, texHeight, textureCount);
        File geoFile = new File(outDir, "geometry.json");
        Files.writeString(geoFile.toPath(), geoJson);

        // Build texture atlas: stack all textures vertically
        File atlasFile = new File(outDir, "texture.png");
        if (textureCount > 1) {
            buildTextureAtlas(modelId, parsedTextures, bbTextures, atlasFile, (int) texWidth, (int) texHeight);
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
        BedrockResourcePackGenerator.generate(modelId, skinsDir, packDir);

        log.info("[Converter] Converted: " + modelId
                + " (textures=" + textureCount
                + ", uvSpace=" + (int) texWidth + "x" + (int) texHeight
                + ", atlasHeight=" + (int) (texHeight * textureCount) + ")");
    }

    /**
     * Builds a texture atlas by stacking all textures vertically.
     * Each texture slot is scaled to match the UV resolution space.
     */
    private void buildTextureAtlas(String modelId, List<ParsedTexture> parsedTextures,
                                    List<?> bbTextures, File outputFile,
                                    int uvWidth, int uvHeight) throws IOException {
        int totalHeight = uvHeight * parsedTextures.size();
        BufferedImage atlas = new BufferedImage(uvWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        for (int i = 0; i < parsedTextures.size(); i++) {
            ParsedTexture pt = parsedTextures.get(i);
            File texFile = getFmmTextureFile(modelId, pt.getFilename());

            if (texFile == null || !texFile.exists()) {
                // Try matching by bbmodel texture name
                if (bbTextures != null && i < bbTextures.size()) {
                    Map<?, ?> bbTex = (Map<?, ?>) bbTextures.get(i);
                    String name = (String) bbTex.get("name");
                    if (name != null) {
                        texFile = getFmmTextureFile(modelId, name);
                    }
                }
            }

            if (texFile != null && texFile.exists()) {
                BufferedImage texImage = ImageIO.read(texFile);
                // Draw scaled to UV space slot
                g.drawImage(texImage, 0, i * uvHeight, uvWidth, uvHeight, null);
                log.info("[Converter] Atlas slot " + i + ": " + texFile.getName()
                        + " (" + texImage.getWidth() + "x" + texImage.getHeight()
                        + " → " + uvWidth + "x" + uvHeight + ")");
            } else {
                log.warning("[Converter] Texture " + i + " not found for model " + modelId
                        + " (filename: " + pt.getFilename() + ")");
                // Fill with magenta for visibility
                g.setColor(Color.MAGENTA);
                g.fillRect(0, i * uvHeight, uvWidth, uvHeight);
            }
        }

        g.dispose();
        ImageIO.write(atlas, "PNG", outputFile);
        log.info("[Converter] Texture atlas created: " + uvWidth + "x" + totalHeight
                + " (" + parsedTextures.size() + " textures)");
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

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0;
    }
}
