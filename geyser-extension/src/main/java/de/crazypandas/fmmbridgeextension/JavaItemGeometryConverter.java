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
        // root to the player's hand/head bone; per-item animations generated from
        // the Java model's display section position geyser_custom_x in that slot.
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
    public Map<String, Object> generateAttachable(JsonObject javaModel, String bedrockKey) {
        Map<String, Object> description = new LinkedHashMap<>();
        String bedrockId = "geyser_custom:" + bedrockKey;
        description.put("identifier", bedrockId);
        // "item" maps this attachable to a specific Bedrock item identifier with a
        // condition. Microsoft's custom_items sample uses it; not strictly required when
        // the attachable identifier already matches the item, but recommended.
        description.put("item", Map.of(bedrockId, "query.is_owner_identifier_any('minecraft:player')"));
        // Materials must use the standard Bedrock names. The official Microsoft sample
        // pairs entity_alphatest (default) with entity_alphatest_glint (enchanted).
        // entity_alphatest_glint_item is NOT a standard Bedrock material — using it
        // causes silent attachable rejection and falls back to 2D icon display.
        Map<String, Object> materials = new LinkedHashMap<>();
        materials.put("default",   "entity_alphatest");
        materials.put("enchanted", "entity_alphatest_glint");
        description.put("materials", materials);

        Map<String, Object> textures = new LinkedHashMap<>();
        textures.put("default",   "textures/items/em/gear/" + bedrockKey);
        textures.put("enchanted", "textures/misc/enchanted_item_glint");
        description.put("textures", textures);
        description.put("geometry",          Map.of("default", "geometry.fmmbridge." + bedrockKey));
        description.put("render_controllers", List.of("controller.render.item_default"));

        String animationPrefix = "animation.fmmbridge.gear." + bedrockKey + ".";
        Map<String, Object> animations = new LinkedHashMap<>();
        animations.put("thirdperson_main_hand", animationPrefix + "thirdperson_main_hand");
        animations.put("thirdperson_off_hand",  animationPrefix + "thirdperson_off_hand");
        animations.put("firstperson_main_hand", animationPrefix + "firstperson_main_hand");
        animations.put("firstperson_off_hand",  animationPrefix + "firstperson_off_hand");
        animations.put("disable",               animationPrefix + "disable");
        if (hasDisplayTransform(javaModel, "head")) {
            animations.put("head", animationPrefix + "head");
        }
        description.put("animations", animations);

        List<String> preAnimation = List.of(
                "v.main_hand = c.item_slot == 'main_hand';",
                "v.off_hand = c.item_slot == 'off_hand';",
                "v.head = c.item_slot == 'head';"
        );
        List<Object> animate = new ArrayList<>();
        animate.add(Map.of("thirdperson_main_hand", "v.main_hand && !c.is_first_person"));
        animate.add(Map.of("thirdperson_off_hand",  "v.off_hand && !c.is_first_person"));
        animate.add(Map.of("firstperson_main_hand", "v.main_hand && c.is_first_person"));
        animate.add(Map.of("firstperson_off_hand",  "v.off_hand && c.is_first_person"));
        if (hasDisplayTransform(javaModel, "head")) {
            animate.add(Map.of("head", "v.head && !c.is_first_person"));
            animate.add(Map.of("disable", "c.is_first_person && v.head"));
        } else {
            animate.add(Map.of("disable", "v.head"));
        }
        Map<String, Object> scripts = new LinkedHashMap<>();
        scripts.put("pre_animation", preAnimation);
        scripts.put("animate", animate);
        description.put("scripts", scripts);

        Map<String, Object> attachable = new LinkedHashMap<>();
        attachable.put("description", description);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.10.0");
        root.put("minecraft:attachable", attachable);
        return root;
    }

    /**
     * Converts Java's per-slot display transforms into attachable animations.
     * The source EM models already contain the item-specific hand/head offsets;
     * reusing them avoids forcing every 3D model through one sprite-oriented pose.
     */
    public Map<String, Object> generateAnimations(JsonObject javaModel, String bedrockKey) {
        JsonObject display = javaModel.has("display") && javaModel.get("display").isJsonObject()
                ? javaModel.getAsJsonObject("display")
                : new JsonObject();

        Map<String, Object> animations = new LinkedHashMap<>();
        String prefix = "animation.fmmbridge.gear." + bedrockKey + ".";
        animations.put(prefix + "thirdperson_main_hand",
                transformAnimation(displayTransform(display, "thirdperson_righthand"), BasePose.THIRD_PERSON_MAIN_HAND));
        animations.put(prefix + "thirdperson_off_hand",
                transformAnimation(displayTransform(display, "thirdperson_lefthand", "thirdperson_righthand"),
                        BasePose.THIRD_PERSON_OFF_HAND));
        animations.put(prefix + "firstperson_main_hand",
                transformAnimation(displayTransform(display, "firstperson_righthand"), BasePose.FIRST_PERSON_MAIN_HAND));
        animations.put(prefix + "firstperson_off_hand",
                transformAnimation(displayTransform(display, "firstperson_lefthand", "firstperson_righthand"),
                        BasePose.FIRST_PERSON_OFF_HAND));
        if (display.has("head") && display.get("head").isJsonObject()) {
            animations.put(prefix + "head", transformAnimation(display.getAsJsonObject("head"), BasePose.HEAD));
        }
        animations.put(prefix + "disable", disableAnimation());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.8.0");
        root.put("animations", animations);
        return root;
    }

    private boolean hasDisplayTransform(JsonObject javaModel, String slot) {
        return javaModel.has("display")
                && javaModel.get("display").isJsonObject()
                && javaModel.getAsJsonObject("display").has(slot)
                && javaModel.getAsJsonObject("display").get(slot).isJsonObject();
    }

    private JsonObject displayTransform(JsonObject display, String primarySlot, String... fallbackSlots) {
        if (display.has(primarySlot) && display.get(primarySlot).isJsonObject()) {
            return display.getAsJsonObject(primarySlot);
        }
        for (String fallbackSlot : fallbackSlots) {
            if (display.has(fallbackSlot) && display.get(fallbackSlot).isJsonObject()) {
                return display.getAsJsonObject(fallbackSlot);
            }
        }
        return new JsonObject();
    }

    private Map<String, Object> transformAnimation(JsonObject displayTransform, BasePose basePose) {
        Map<String, Object> animation = new LinkedHashMap<>();
        animation.put("loop", true);

        Map<String, Object> bones = new LinkedHashMap<>();
        bones.put("geyser_custom_x", composeDelta(basePose.xBone(), displayTransform));
        bones.put("geyser_custom_y", cloneBone(basePose.yBone()));
        bones.put("geyser_custom_z", cloneBone(basePose.zBone()));
        bones.put("geyser_custom", cloneBone(basePose.rootBone()));
        animation.put("bones", bones);
        return animation;
    }

    private Map<String, Object> disableAnimation() {
        Map<String, Object> animation = new LinkedHashMap<>();
        animation.put("loop", true);

        Map<String, Object> hiddenBone = new LinkedHashMap<>();
        hiddenBone.put("scale", 0);

        Map<String, Object> bones = new LinkedHashMap<>();
        bones.put("geyser_custom_x", hiddenBone);
        animation.put("bones", bones);
        return animation;
    }

    private Map<String, Object> composeDelta(BoneTransform base, JsonObject displayTransform) {
        Map<String, Object> composed = cloneBone(base);

        double[] rotationDelta = readVector(displayTransform, "rotation");
        if (rotationDelta != null) {
            composed.put("rotation", addVectors(base.rotation(), rotationDelta));
        }

        double[] translationDelta = readVector(displayTransform, "translation");
        if (translationDelta != null) {
            composed.put("position", addVectors(base.position(), translationDelta));
        }

        double[] scaleDelta = readVector(displayTransform, "scale");
        if (scaleDelta != null) {
            composed.put("scale", multiplyVectors(base.scale(), scaleDelta));
        }
        return composed;
    }

    private Map<String, Object> cloneBone(BoneTransform transform) {
        Map<String, Object> bone = new LinkedHashMap<>();
        putVectorIfPresent(bone, "rotation", transform.rotation());
        putVectorIfPresent(bone, "position", transform.position());
        putVectorIfPresent(bone, "scale", transform.scale());
        return bone;
    }

    private void putVectorIfPresent(Map<String, Object> target, String key, double[] vector) {
        if (vector != null) {
            target.put(key, vector.clone());
        }
    }

    private double[] readVector(JsonObject source, String sourceKey) {
        if (!source.has(sourceKey) || !source.get(sourceKey).isJsonArray()) {
            return null;
        }
        JsonArray vector = source.getAsJsonArray(sourceKey);
        if (vector.size() != 3) {
            return null;
        }
        return new double[]{
                vector.get(0).getAsDouble(),
                vector.get(1).getAsDouble(),
                vector.get(2).getAsDouble()
        };
    }

    private double[] addVectors(double[] base, double[] delta) {
        double[] left = base != null ? base : new double[]{0, 0, 0};
        return new double[]{
                left[0] + delta[0],
                left[1] + delta[1],
                left[2] + delta[2]
        };
    }

    private double[] multiplyVectors(double[] base, double[] factor) {
        double[] left = base != null ? base : new double[]{1, 1, 1};
        return new double[]{
                left[0] * factor[0],
                left[1] * factor[1],
                left[2] * factor[2]
        };
    }

    private record BoneTransform(double[] rotation, double[] position, double[] scale) {
    }

    private enum BasePose {
        THIRD_PERSON_MAIN_HAND(
                new BoneTransform(new double[]{0, 0, 0}, new double[]{0, 4, 2.5}, new double[]{0.85, 0.85, 0.85}),
                new BoneTransform(new double[]{0, -90, 0}, null, null),
                new BoneTransform(new double[]{0, 0, 55}, null, null),
                new BoneTransform(new double[]{90, 0, 0}, new double[]{0, 13, -3}, null)),
        THIRD_PERSON_OFF_HAND(
                new BoneTransform(new double[]{0, 0, 0}, new double[]{0, 4, 2.5}, new double[]{0.85, 0.85, 0.85}),
                new BoneTransform(new double[]{0, 90, 0}, null, null),
                new BoneTransform(new double[]{0, 0, -55}, null, null),
                new BoneTransform(new double[]{90, 0, 0}, new double[]{0, 13, -3}, null)),
        FIRST_PERSON_MAIN_HAND(
                new BoneTransform(null, new double[]{0, 1.6, -0.8}, new double[]{0.68, 0.68, 0.68}),
                new BoneTransform(new double[]{0, -90, 0}, null, null),
                new BoneTransform(new double[]{0, 0, 25}, null, null),
                new BoneTransform(new double[]{53.79601, 51.7101, -83.00307},
                        new double[]{-2, 12, 5}, new double[]{1.5, 1.5, 1.5})),
        FIRST_PERSON_OFF_HAND(
                new BoneTransform(null, new double[]{0, 1.6, -0.8}, new double[]{0.68, 0.68, 0.68}),
                new BoneTransform(new double[]{0, 90, 0}, null, null),
                new BoneTransform(new double[]{0, 0, -25}, null, null),
                new BoneTransform(new double[]{90, 60, -40},
                        new double[]{4, 10, 4}, new double[]{1.5, 1.5, 1.5})),
        HEAD(
                new BoneTransform(null, null, new double[]{0.625, 0.625, 0.625}),
                new BoneTransform(null, null, null),
                new BoneTransform(null, null, null),
                new BoneTransform(null, new double[]{0, 19.9, 0}, null));

        private final BoneTransform xBone;
        private final BoneTransform yBone;
        private final BoneTransform zBone;
        private final BoneTransform rootBone;

        BasePose(BoneTransform xBone, BoneTransform yBone, BoneTransform zBone, BoneTransform rootBone) {
            this.xBone = xBone;
            this.yBone = yBone;
            this.zBone = zBone;
            this.rootBone = rootBone;
        }

        private BoneTransform xBone() {
            return xBone;
        }

        private BoneTransform yBone() {
            return yBone;
        }

        private BoneTransform zBone() {
            return zBone;
        }

        private BoneTransform rootBone() {
            return rootBone;
        }
    }
}
