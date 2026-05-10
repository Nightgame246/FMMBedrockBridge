package de.crazypandas.fmmbridgeextension;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * Converts a Java item model JSON (elements array) to Bedrock geo.json and attachable JSON.
 * Each Java element becomes a separate Bedrock bone so per-element rotations are handled correctly.
 * Coordinate mapping: bedrock_coord = java_coord - 8 (Java item space is centred at 8,8,8).
 */
public class JavaItemGeometryConverter {

    private static final List<String> FACE_NAMES = List.of("north", "south", "east", "west", "up", "down");

    /**
     * Converts Java model JSON to a Bedrock geo.json structure (ready for Gson serialisation).
     *
     * @param javaModel  parsed Java model JSON object (must contain "elements")
     * @param bedrockKey identifier suffix, e.g. "em_bronze_sword"
     */
    public Map<String, Object> convertToGeo(JsonObject javaModel, String bedrockKey) {
        int texW = 64, texH = 64;
        if (javaModel.has("texture_size")) {
            JsonArray ts = javaModel.getAsJsonArray("texture_size");
            texW = ts.get(0).getAsInt();
            texH = ts.get(1).getAsInt();
        }

        JsonArray elements = javaModel.getAsJsonArray("elements");
        List<Map<String, Object>> bones = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            bones.add(convertElement(elements.get(i).getAsJsonObject(), i));
        }

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", "geometry.fmmbridge." + bedrockKey);
        description.put("texture_width", texW);
        description.put("texture_height", texH);
        description.put("visible_bounds_width", 4);
        description.put("visible_bounds_height", 4);
        description.put("visible_bounds_offset", new int[]{0, 2, 0});

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("description", description);
        geometry.put("bones", bones);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.12.0");
        root.put("minecraft:geometry", List.of(geometry));
        return root;
    }

    private Map<String, Object> convertElement(JsonObject el, int index) {
        JsonArray from = el.getAsJsonArray("from");
        JsonArray to   = el.getAsJsonArray("to");

        // Cube origin (min corner) and size — centred at 0
        double ox = from.get(0).getAsDouble() - 8;
        double oy = from.get(1).getAsDouble() - 8;
        double oz = from.get(2).getAsDouble() - 8;
        double sx = to.get(0).getAsDouble() - from.get(0).getAsDouble();
        double sy = to.get(1).getAsDouble() - from.get(1).getAsDouble();
        double sz = to.get(2).getAsDouble() - from.get(2).getAsDouble();

        Map<String, Object> cube = new LinkedHashMap<>();
        cube.put("origin", new double[]{ox, oy, oz});
        cube.put("size",   new double[]{sx, sy, sz});

        // Per-face UV: Java [u1,v1,u2,v2] → Bedrock uv:[u1,v1] uv_size:[w,h]
        if (el.has("faces")) {
            Map<String, Object> uvMap = new LinkedHashMap<>();
            JsonObject faces = el.getAsJsonObject("faces");
            for (String face : FACE_NAMES) {
                if (!faces.has(face)) continue;
                JsonObject f = faces.getAsJsonObject(face);
                if (!f.has("uv")) continue;
                JsonArray uv = f.getAsJsonArray("uv");
                double u1 = uv.get(0).getAsDouble(), v1 = uv.get(1).getAsDouble();
                double u2 = uv.get(2).getAsDouble(), v2 = uv.get(3).getAsDouble();
                Map<String, Object> faceEntry = new LinkedHashMap<>();
                faceEntry.put("uv",      new double[]{u1, v1});
                faceEntry.put("uv_size", new double[]{u2 - u1, v2 - v1});
                uvMap.put(face, faceEntry);
            }
            cube.put("uv", uvMap);
        }

        // Bone — one per element so rotation origins work correctly
        double[] pivot       = {0, 0, 0};
        double[] boneRotation = {0, 0, 0};
        if (el.has("rotation")) {
            JsonObject rot = el.getAsJsonObject("rotation");
            JsonArray origin = rot.getAsJsonArray("origin");
            pivot = new double[]{
                origin.get(0).getAsDouble() - 8,
                origin.get(1).getAsDouble() - 8,
                origin.get(2).getAsDouble() - 8
            };
            double angle = rot.get("angle").getAsDouble();
            switch (rot.get("axis").getAsString()) {
                case "x" -> boneRotation = new double[]{angle, 0, 0};
                case "y" -> boneRotation = new double[]{0, angle, 0};
                case "z" -> boneRotation = new double[]{0, 0, angle};
            }
        }

        Map<String, Object> bone = new LinkedHashMap<>();
        bone.put("name",  "bone_" + index);
        bone.put("pivot", pivot);
        if (boneRotation[0] != 0 || boneRotation[1] != 0 || boneRotation[2] != 0) {
            bone.put("rotation", boneRotation);
        }
        bone.put("cubes", List.of(cube));
        return bone;
    }

    /**
     * Generates a Bedrock attachable definition for a gear item.
     * The attachable tells Bedrock to render the 3D geometry when the player holds this custom item.
     *
     * @param bedrockKey e.g. "em_bronze_sword"
     */
    public Map<String, Object> generateAttachable(String bedrockKey) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier",        "fmmbridge:" + bedrockKey);
        description.put("materials",         Map.of("default", "entity_alphatest"));
        description.put("textures",          Map.of("default", "textures/items/em/gear/" + bedrockKey));
        description.put("geometry",          Map.of("default", "geometry.fmmbridge." + bedrockKey));
        description.put("render_controllers", List.of("controller.render.item_default"));

        Map<String, Object> attachable = new LinkedHashMap<>();
        attachable.put("description", description);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", "1.10.0");
        root.put("minecraft:attachable", attachable);
        return root;
    }
}
