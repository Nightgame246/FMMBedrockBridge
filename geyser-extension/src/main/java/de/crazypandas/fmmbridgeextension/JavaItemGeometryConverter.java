package de.crazypandas.fmmbridgeextension;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Java item model JSON (elements array) to Bedrock geo.json and attachable JSON.
 * Each Java element becomes a separate Bedrock bone so per-element rotations are handled correctly.
 * Coordinate mapping: bedrock_coord = java_coord - 8 (Java item space is centred at 8,8,8).
 */
public class JavaItemGeometryConverter {

    private static final List<String> FACE_NAMES = List.of("north", "south", "east", "west", "up", "down");

    /**
     * Converts a Java item model JSON ({@code elements} array) to a Bedrock geo.json
     * structure (ready for Gson serialisation).
     *
     * <p>The cubes are nested under the standard Geyser custom-item binding hierarchy
     * (geyser_custom / _x / _y / _z) so the attachable attaches them to the player's
     * hand; non-rotated elements become cubes inside geyser_custom_z, each rotated
     * element becomes a child bone of geyser_custom_z. This matches the Geyser
     * java2bedrock output verified against the reference pack.
     *
     * @param javaModel  parsed Java model JSON object (expects an "elements" array)
     * @param bedrockKey identifier suffix, e.g. "em_bronze_sword"
     */
    public Map<String, Object> convertToGeo(JsonObject javaModel, String bedrockKey) {
        // Texture dimensions come from the Java model's texture_size (EM gear: 64x64
        // per frame). Bedrock per-face UVs live in this same pixel space, copied as-is.
        int texW = 64, texH = 64;
        if (javaModel.has("texture_size")) {
            JsonArray ts = javaModel.getAsJsonArray("texture_size");
            texW = ts.get(0).getAsInt();
            texH = ts.get(1).getAsInt();
        }

        // Non-rotated elements become cubes inside geyser_custom_z. Each rotated
        // element becomes its own child bone of geyser_custom_z — Bedrock applies
        // rotation per bone, not per cube — matching the Geyser java2bedrock output.
        List<Map<String, Object>> zCubes = new ArrayList<>();
        List<Map<String, Object>> rotationBones = new ArrayList<>();
        if (javaModel.has("elements")) {
            JsonArray elements = javaModel.getAsJsonArray("elements");
            for (int i = 0; i < elements.size(); i++) {
                JsonObject el = elements.get(i).getAsJsonObject();
                Map<String, Object> cube = convertCube(el);
                double[] boneRotation = elementRotation(el);
                if (boneRotation == null) {
                    zCubes.add(cube);
                } else {
                    JsonArray origin = el.getAsJsonObject("rotation").getAsJsonArray("origin");
                    Map<String, Object> rotBone = new LinkedHashMap<>();
                    rotBone.put("name", "geyser_custom_el_" + i);
                    rotBone.put("parent", "geyser_custom_z");
                    rotBone.put("pivot", new double[]{
                            origin.get(0).getAsDouble() - 8,
                            origin.get(1).getAsDouble(),
                            origin.get(2).getAsDouble() - 8});
                    rotBone.put("rotation", boneRotation);
                    rotBone.put("cubes", List.of(cube));
                    rotationBones.add(rotBone);
                }
            }
        }

        // Standard Geyser custom-item binding hierarchy. The binding attaches the
        // root to the player's hand/head bone; the shared gear animations
        // (animations/fmmbridge_gear.json) position geyser_custom_x/y/z in hand.
        Map<String, Object> boneRoot = bone("geyser_custom", null);
        boneRoot.put("binding", "c.item_slot == 'head' ? 'head' : q.item_slot_to_bone_name(c.item_slot)");
        Map<String, Object> boneX = bone("geyser_custom_x", "geyser_custom");
        Map<String, Object> boneY = bone("geyser_custom_y", "geyser_custom_x");
        Map<String, Object> boneZ = bone("geyser_custom_z", "geyser_custom_y");
        boneZ.put("cubes", zCubes);

        List<Map<String, Object>> bones = new ArrayList<>();
        bones.add(boneRoot);
        bones.add(boneX);
        bones.add(boneY);
        bones.add(boneZ);
        bones.addAll(rotationBones);

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", "geometry.fmmbridge." + bedrockKey);
        description.put("texture_width", texW);
        description.put("texture_height", texH);
        description.put("visible_bounds_width", 4);
        description.put("visible_bounds_height", 4.5);
        description.put("visible_bounds_offset", new double[]{0, 0.75, 0});

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("description", description);
        geometry.put("bones", bones);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.16.0");
        root.put("minecraft:geometry", List.of(geometry));
        return root;
    }

    /** A bone with the standard [0,8,0] pivot, optionally parented. */
    private Map<String, Object> bone(String name, String parent) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        if (parent != null) b.put("parent", parent);
        b.put("pivot", new double[]{0, 8, 0});
        return b;
    }

    /**
     * Converts a Java element to a Bedrock cube. Java item space is centred at
     * 8,8,8, so x/z shift by -8 while y is kept. Per-face UV [u1,v1,u2,v2]
     * becomes {"uv":[u1,v1],"uv_size":[u2-u1,v2-v1]} — negative sizes are kept,
     * Bedrock reads them as mirrored faces.
     */
    private Map<String, Object> convertCube(JsonObject el) {
        JsonArray from = el.getAsJsonArray("from");
        JsonArray to = el.getAsJsonArray("to");
        double fx = from.get(0).getAsDouble(), fy = from.get(1).getAsDouble(), fz = from.get(2).getAsDouble();
        double tx = to.get(0).getAsDouble(), ty = to.get(1).getAsDouble(), tz = to.get(2).getAsDouble();

        Map<String, Object> cube = new LinkedHashMap<>();
        cube.put("origin", new double[]{fx - 8, fy, fz - 8});
        cube.put("size", new double[]{tx - fx, ty - fy, tz - fz});

        if (el.has("faces")) {
            JsonObject faces = el.getAsJsonObject("faces");
            Map<String, Object> uv = new LinkedHashMap<>();
            for (String face : FACE_NAMES) {
                if (!faces.has(face)) continue;
                JsonObject f = faces.getAsJsonObject(face);
                if (!f.has("uv")) continue;
                JsonArray u = f.getAsJsonArray("uv");
                double u1 = u.get(0).getAsDouble(), v1 = u.get(1).getAsDouble();
                double u2 = u.get(2).getAsDouble(), v2 = u.get(3).getAsDouble();
                Map<String, Object> faceEntry = new LinkedHashMap<>();
                faceEntry.put("uv", new double[]{u1, v1});
                faceEntry.put("uv_size", new double[]{u2 - u1, v2 - v1});
                uv.put(face, faceEntry);
            }
            if (!uv.isEmpty()) cube.put("uv", uv);
        }
        return cube;
    }

    /**
     * Java element rotation → Bedrock bone rotation, or null when the element has
     * no rotation (or a zero angle). Java allows one axis at a fixed set of
     * angles; Bedrock's convention negates X and Y and keeps Z (the java2bedrock
     * rule, verified against the reference pack's ultimatium_axe).
     */
    private double[] elementRotation(JsonObject el) {
        if (!el.has("rotation")) return null;
        JsonObject rot = el.getAsJsonObject("rotation");
        if (!rot.has("angle")) return null;
        double angle = rot.get("angle").getAsDouble();
        if (angle == 0) return null;
        String axis = rot.has("axis") ? rot.get("axis").getAsString() : "y";
        return switch (axis) {
            case "x" -> new double[]{-angle, 0, 0};
            case "y" -> new double[]{0, -angle, 0};
            case "z" -> new double[]{0, 0, angle};
            default -> null;
        };
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
