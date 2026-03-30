package de.crazypandas.fmmbedrockbridge.converter;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.ParsedTexture;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Converts FMM models (.bbmodel) to Bedrock geometry + texture files
 * compatible with the GeyserUtils skin system.
 *
 * Output per model:
 *   <output-path>/<modelId>/geometry.json
 *   <output-path>/<modelId>/texture.png
 */
public class BedrockModelConverter {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private static final Gson GSON = new Gson();

    // Bone name prefixes that should not be included in the Bedrock model
    private static final Set<String> SKIP_PREFIXES = Set.of("b_", "hitbox", "tag_", "m_");

    private final File outputBase;

    public BedrockModelConverter() {
        String configPath = FMMBedrockBridge.getInstance().getConfig().getString("converter.output-path", "bedrock-skins");
        this.outputBase = new File(FMMBedrockBridge.getInstance().getDataFolder(), configPath);
    }

    /**
     * Convert all loaded FMM models.
     */
    public void convertAll() {
        Map<String, FileModelConverter> models = FileModelConverter.getConvertedFileModels();
        if (models.isEmpty()) {
            log.warning("[Converter] No FMM models found.");
            return;
        }
        log.info("[Converter] Converting " + models.size() + " FMM model(s)...");
        for (String modelId : models.keySet()) {
            try {
                convert(modelId);
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
    public void convert(String modelId) throws IOException {
        FileModelConverter fmm = FileModelConverter.getConvertedFileModels().get(modelId);
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

        // Get texture dimensions from FMM
        List<ParsedTexture> parsedTextures = fmm.getParsedTextures();
        if (parsedTextures.isEmpty()) {
            log.warning("[Converter] No textures found for model: " + modelId);
            return;
        }
        ParsedTexture firstTexture = parsedTextures.get(0);
        double texWidth = firstTexture.getTextureWidth();
        double texHeight = firstTexture.getTextureHeight();

        // Create output folder
        File outDir = new File(outputBase, modelId);
        outDir.mkdirs();

        // Generate and write geometry.json
        String geoJson = BedrockGeometryGenerator.generate(modelId, bbmodel, texWidth, texHeight);
        File geoFile = new File(outDir, "geometry.json");
        Files.writeString(geoFile.toPath(), geoJson);

        // Copy texture from FMM's output
        File textureFile = getFmmTextureFile(modelId, firstTexture.getFilename());
        if (textureFile != null && textureFile.exists()) {
            Files.copy(textureFile.toPath(), new File(outDir, "texture.png").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            log.warning("[Converter] Texture file not found for model: " + modelId
                    + " (expected: " + (textureFile != null ? textureFile.getAbsolutePath() : "null") + ")");
        }

        Path skinsDir = outDir.toPath();
        Path packDir = FMMBedrockBridge.getInstance().getDataFolder().toPath().resolve("bedrock-pack");
        BedrockResourcePackGenerator.generate(modelId, skinsDir, packDir);

        log.info("[Converter] Converted: " + modelId + " → " + outDir.getAbsolutePath());
    }

    /**
     * Finds the texture PNG that FMM wrote during its own startup.
     * Path: plugins/FreeMinecraftModels/output/FreeMinecraftModels/assets/freeminecraftmodels/textures/entity/<modelId>/<filename>
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
}
