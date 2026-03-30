package de.crazypandas.fmmbedrockbridge.converter;

import com.google.gson.*;

import java.util.*;

/**
 * Generates a Bedrock .geo.json from raw .bbmodel data.
 *
 * Coordinate mapping:
 *   .bbmodel element from/to/origin → Bedrock geo origin/size/pivot (direct, no scaling)
 *   .bbmodel UV [u1,v1,u2,v2] (pixel space) → Bedrock {"uv":[u1,v1],"uv_size":[u2-u1,v2-v1]}
 *
 * Skips meta-bones: b_*, hitbox*, tag_*, m_*
 */
public class BedrockGeometryGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Prefixes of bones that should not be included in Bedrock geometry
    private static final List<String> SKIP_PREFIXES = List.of("b_", "hitbox", "tag_", "m_");

    public static String generate(String modelId, Map<?, ?> bbmodel, double texWidth, double texHeight) {
        // Build element map: UUID → element data
        Map<String, Map<?, ?>> elementMap = new HashMap<>();
        List<?> elements = (List<?>) bbmodel.get("elements");
        if (elements != null) {
            for (Object elem : elements) {
                Map<?, ?> e = (Map<?, ?>) elem;
                String uuid = (String) e.get("uuid");
                if (uuid != null) elementMap.put(uuid, e);
            }
        }

        // Build bones list by traversing outliner recursively
        List<JsonObject> bones = new ArrayList<>();
        List<?> outliner = (List<?>) bbmodel.get("outliner");
        if (outliner != null) {
            traverseOutliner(outliner, null, elementMap, bones, texWidth, texHeight);
        }

        // Build geometry JSON
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");

        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.fmmbridge." + modelId);
        description.addProperty("texture_width", (int) texWidth);
        description.addProperty("texture_height", (int) texHeight);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        description.add("visible_bounds_offset", toJsonArray(0.0, 1.5, 0.0));

        JsonObject geometryEntry = new JsonObject();
        geometryEntry.add("description", description);

        JsonArray bonesArray = new JsonArray();
        for (JsonObject bone : bones) {
            bonesArray.add(bone);
        }
        geometryEntry.add("bones", bonesArray);

        JsonArray geometryList = new JsonArray();
        geometryList.add(geometryEntry);

        root.add("minecraft:geometry", geometryList);

        return GSON.toJson(root);
    }

    private static void traverseOutliner(List<?> items, String parentName,
                                          Map<String, Map<?, ?>> elementMap,
                                          List<JsonObject> bones,
                                          double texWidth, double texHeight) {
        for (Object item : items) {
            if (item instanceof Map<?, ?> group) {
                // It's a bone group
                String boneName = (String) group.get("name");
                if (boneName == null) continue;
                if (shouldSkip(boneName)) continue;

                // Safe bone name for Bedrock (no special chars)
                String safeName = safeBoneName(boneName);

                JsonObject bone = new JsonObject();
                bone.addProperty("name", safeName);
                if (parentName != null) bone.addProperty("parent", parentName);

                // Pivot point from bone origin
                List<?> origin = (List<?>) group.get("origin");
                if (origin != null && origin.size() == 3) {
                    bone.add("pivot", toJsonArray(
                            toDouble(origin.get(0)),
                            toDouble(origin.get(1)),
                            toDouble(origin.get(2))));
                } else {
                    bone.add("pivot", toJsonArray(0, 0, 0));
                }

                // Bone rotation (optional)
                List<?> rotation = (List<?>) group.get("rotation");
                if (rotation != null && rotation.size() == 3) {
                    double rx = toDouble(rotation.get(0));
                    double ry = toDouble(rotation.get(1));
                    double rz = toDouble(rotation.get(2));
                    if (rx != 0 || ry != 0 || rz != 0) {
                        bone.add("rotation", toJsonArray(rx, ry, rz));
                    }
                }

                // Collect cubes from this bone's children
                List<?> children = (List<?>) group.get("children");
                if (children != null) {
                    JsonArray cubesArray = new JsonArray();
                    List<?> boneOrigin = (List<?>) group.get("origin");
                    for (Object child : children) {
                        if (child instanceof String uuid) {
                            // It's a reference to a cube element
                            Map<?, ?> element = elementMap.get(uuid);
                            if (element != null) {
                                String type = (String) element.get("type");
                                if (!"locator".equals(type) && !"null_object".equals(type)) {
                                    JsonObject cube = buildCube(element, boneOrigin, texWidth, texHeight);
                                    if (cube != null) cubesArray.add(cube);
                                }
                            }
                        }
                    }
                    if (cubesArray.size() > 0) bone.add("cubes", cubesArray);

                    bones.add(bone);

                    // Recurse into child bone groups
                    traverseOutliner(children, safeName, elementMap, bones, texWidth, texHeight);
                } else {
                    bones.add(bone);
                }

            }
            // Strings (UUID references at outliner root level) are element references without a bone — skip
        }
    }

    private static JsonObject buildCube(Map<?, ?> element, List<?> boneOrigin,
                                         double texWidth, double texHeight) {
        List<?> fromList = (List<?>) element.get("from");
        List<?> toList = (List<?>) element.get("to");
        if (fromList == null || toList == null) return null;

        double fromX = toDouble(fromList.get(0));
        double fromY = toDouble(fromList.get(1));
        double fromZ = toDouble(fromList.get(2));
        double toX = toDouble(toList.get(0));
        double toY = toDouble(toList.get(1));
        double toZ = toDouble(toList.get(2));

        // Apply inflate if present
        double inflate = 0;
        if (element.get("inflate") != null) {
            inflate = toDouble(element.get("inflate"));
        }
        fromX -= inflate; fromY -= inflate; fromZ -= inflate;
        toX += inflate; toY += inflate; toZ += inflate;

        // Bedrock origin = min corner (same as bbmodel from, accounting for inflate)
        // Bedrock size = to - from
        double sizeX = toX - fromX;
        double sizeY = toY - fromY;
        double sizeZ = toZ - fromZ;

        JsonObject cube = new JsonObject();
        cube.add("origin", toJsonArray(fromX, fromY, fromZ));
        cube.add("size", toJsonArray(sizeX, sizeY, sizeZ));

        // Cube pivot + rotation (if present in bbmodel)
        List<?> cubeOrigin = (List<?>) element.get("origin");
        List<?> cubeRotation = (List<?>) element.get("rotation");
        if (cubeOrigin != null && cubeRotation != null && cubeRotation.size() == 3) {
            double rx = toDouble(cubeRotation.get(0));
            double ry = toDouble(cubeRotation.get(1));
            double rz = toDouble(cubeRotation.get(2));
            if (rx != 0 || ry != 0 || rz != 0) {
                cube.add("pivot", toJsonArray(
                        toDouble(cubeOrigin.get(0)),
                        toDouble(cubeOrigin.get(1)),
                        toDouble(cubeOrigin.get(2))));
                cube.add("rotation", toJsonArray(rx, ry, rz));
            }
        }

        // UV mapping: per-face
        Map<?, ?> faces = (Map<?, ?>) element.get("faces");
        if (faces != null) {
            JsonObject uvObj = new JsonObject();
            for (String face : new String[]{"north", "south", "east", "west", "up", "down"}) {
                Map<?, ?> faceData = (Map<?, ?>) faces.get(face);
                if (faceData == null) continue;
                List<?> uv = (List<?>) faceData.get("uv");
                if (uv == null || uv.size() < 4) continue;

                double u1 = toDouble(uv.get(0));
                double v1 = toDouble(uv.get(1));
                double u2 = toDouble(uv.get(2));
                double v2 = toDouble(uv.get(3));

                JsonObject faceUV = new JsonObject();
                faceUV.add("uv", toJsonArray(u1, v1));
                faceUV.add("uv_size", toJsonArray(u2 - u1, v2 - v1));
                uvObj.add(face, faceUV);
            }
            cube.add("uv", uvObj);
        }

        return cube;
    }

    private static boolean shouldSkip(String boneName) {
        String lower = boneName.toLowerCase();
        for (String prefix : SKIP_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String safeBoneName(String name) {
        // Bedrock bone names: alphanumeric + underscore only
        return name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0;
    }

    private static JsonArray toJsonArray(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(round(x));
        a.add(round(y));
        a.add(round(z));
        return a;
    }

    private static JsonArray toJsonArray(double x, double y) {
        JsonArray a = new JsonArray();
        a.add(round(x));
        a.add(round(y));
        return a;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
