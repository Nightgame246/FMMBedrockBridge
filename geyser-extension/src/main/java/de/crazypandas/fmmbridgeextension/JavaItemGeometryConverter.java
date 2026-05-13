package de.crazypandas.fmmbridgeextension;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Java item model JSON (elements array) to Bedrock geo.json and attachable JSON.
 * Each Java element becomes a separate Bedrock bone so per-element rotations are handled correctly.
 * Coordinate mapping: bedrock_coord = java_coord - 8 (Java item space is centred at 8,8,8).
 */
public class JavaItemGeometryConverter {

    /**
     * Converts Java model JSON to a Bedrock geo.json structure (ready for Gson serialisation).
     *
     * EM gear item textures are 2D sprite sheets with sub-pixel UV regions in their 3D elements;
     * converting those elements produces near-invisible geometry in Bedrock. Instead we generate
     * a flat sprite quad that shows the full texture — matching how Java displays items in hand.
     *
     * @param javaModel  parsed Java model JSON object
     * @param bedrockKey identifier suffix, e.g. "em_bronze_sword"
     */
    public Map<String, Object> convertToGeo(JsonObject javaModel, String bedrockKey) {
        // Bone structure matches the GeyserUtils/NitroSetups convention exactly:
        //   geyser_custom (root, binding → player hand bone)
        //     geyser_custom_x (child)
        //       geyser_custom_y (child)
        //         geyser_custom_z (child, has texture_meshes flat sprite)
        //
        // The binding field attaches the root to the correct player hand/head bone at
        // runtime. texture_meshes maps the full default texture as a flat sprite.
        // Format 1.16.0 is required for both binding and texture_meshes support.

        Map<String, Object> textureMesh = new LinkedHashMap<>();
        textureMesh.put("texture",     "default");
        textureMesh.put("position",    new double[]{0, 8, 0});
        textureMesh.put("rotation",    new double[]{90, 0, -180});
        textureMesh.put("local_pivot", new double[]{8, 0.5, 8});

        Map<String, Object> boneRoot = new LinkedHashMap<>();
        boneRoot.put("name",    "geyser_custom");
        boneRoot.put("pivot",   new double[]{0, 8, 0});
        boneRoot.put("binding", "c.item_slot == 'head' ? 'head' : q.item_slot_to_bone_name(c.item_slot)");

        Map<String, Object> boneX = new LinkedHashMap<>();
        boneX.put("name",   "geyser_custom_x");
        boneX.put("parent", "geyser_custom");
        boneX.put("pivot",  new double[]{0, 8, 0});

        Map<String, Object> boneY = new LinkedHashMap<>();
        boneY.put("name",   "geyser_custom_y");
        boneY.put("parent", "geyser_custom_x");
        boneY.put("pivot",  new double[]{0, 8, 0});

        Map<String, Object> boneZ = new LinkedHashMap<>();
        boneZ.put("name",          "geyser_custom_z");
        boneZ.put("parent",        "geyser_custom_y");
        boneZ.put("pivot",         new double[]{0, 8, 0});
        boneZ.put("texture_meshes", List.of(textureMesh));

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", "geometry.fmmbridge." + bedrockKey);
        description.put("texture_width",         16);
        description.put("texture_height",        16);
        description.put("visible_bounds_width",  4);
        description.put("visible_bounds_height", 4.5);
        description.put("visible_bounds_offset", new double[]{0, 0.75, 0});

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("description", description);
        geometry.put("bones", List.of(boneRoot, boneX, boneY, boneZ));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.16.0");
        root.put("minecraft:geometry", List.of(geometry));
        return root;
    }

    /**
     * Generates a Bedrock attachable definition for a gear item.
     * The attachable tells Bedrock to render the 3D geometry when the player holds this custom item.
     *
     * @param bedrockKey e.g. "em_bronze_sword"
     */
    public Map<String, Object> generateAttachable(String bedrockKey) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier",        "geyser_custom:" + bedrockKey);
        // controller.render.item_default (Bedrock 1.21+) requires both "default" and "enchanted"
        // entries in materials and textures; missing either causes a Molang error that silently
        // falls back to 2D icon display instead of rendering the custom geometry.
        Map<String, Object> materials = new LinkedHashMap<>();
        materials.put("default",   "entity_alphatest");
        materials.put("enchanted", "entity_alphatest_glint_item");
        description.put("materials", materials);

        Map<String, Object> textures = new LinkedHashMap<>();
        textures.put("default",   "textures/items/em/gear/" + bedrockKey);
        textures.put("enchanted", "textures/misc/enchanted_item_glint");
        description.put("textures", textures);
        description.put("geometry",          Map.of("default", "geometry.fmmbridge." + bedrockKey));
        description.put("render_controllers", List.of("controller.render.item_default"));

        // Shared positioning animations (defined once in animations/fmmbridge_gear.json).
        // All gear items use the same bone names (geyser_custom_x/y/z/geyser_custom) so
        // one animation set covers all of them.
        Map<String, Object> animations = new LinkedHashMap<>();
        animations.put("thirdperson_main_hand", "animation.fmmbridge.gear.thirdperson_main_hand");
        animations.put("thirdperson_off_hand",  "animation.fmmbridge.gear.thirdperson_off_hand");
        animations.put("head",                  "animation.fmmbridge.gear.head");
        animations.put("firstperson_main_hand", "animation.fmmbridge.gear.firstperson_main_hand");
        animations.put("firstperson_off_hand",  "animation.fmmbridge.gear.firstperson_off_hand");
        animations.put("disable",               "animation.fmmbridge.gear.disable");
        description.put("animations", animations);

        List<String> preAnimation = List.of(
                "v.main_hand = c.item_slot == 'main_hand';",
                "v.off_hand = c.item_slot == 'off_hand';",
                "v.head = c.item_slot == 'head';"
        );
        List<Object> animate = List.of(
                Map.of("thirdperson_main_hand", "v.main_hand && !c.is_first_person"),
                Map.of("thirdperson_off_hand",  "v.off_hand && !c.is_first_person"),
                Map.of("head",                  "v.head && !c.is_first_person"),
                Map.of("firstperson_main_hand", "v.main_hand && c.is_first_person"),
                Map.of("firstperson_off_hand",  "v.off_hand && c.is_first_person"),
                Map.of("disable",               "c.is_first_person && v.head")
        );
        Map<String, Object> scripts = new LinkedHashMap<>();
        scripts.put("pre_animation", preAnimation);
        scripts.put("animate",       animate);
        description.put("scripts", scripts);

        Map<String, Object> attachable = new LinkedHashMap<>();
        attachable.put("description", description);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.10.0");
        root.put("minecraft:attachable", attachable);
        return root;
    }
}
