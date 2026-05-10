package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Represents a single EliteMobs custom item mapping:
 * Java item + custom_model_data integer → Bedrock texture key.
 */
public record EMCustomItem(
        String javaMaterial,      // e.g. "minecraft:diamond_sword"
        int customModelData,      // e.g. 1001
        String sourceTexturePath, // absolute path to PNG on disk
        String bedrockTextureKey  // e.g. "em_diamond_sword_1001"
) {}
