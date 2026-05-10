package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Represents a single EliteMobs 3D gear item mapping:
 * Java item + custom_model_data → Bedrock geometry key.
 * sourceModelPath and sourceTexturePath are relative to the bedrock-pack output directory.
 */
public record EMGearItem(
        String javaMaterial,      // e.g. "minecraft:diamond_sword"
        int customModelData,      // e.g. 1
        String bedrockKey,        // e.g. "em_bronze_sword"
        String sourceModelPath,   // relative: "em-gear-models/em_bronze_sword.json"
        String sourceTexturePath  // relative: "em-gear-textures/em_bronze_sword.png"
) {}
