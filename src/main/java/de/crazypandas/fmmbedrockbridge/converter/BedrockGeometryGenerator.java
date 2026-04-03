package de.crazypandas.fmmbedrockbridge.converter;

import com.google.gson.*;

import java.util.*;

/**
 * Generates a Bedrock .geo.json from raw .bbmodel data.
 *
 * Handles multi-texture models by using a texture atlas:
 * - All textures are stacked vertically in the atlas
 * - UV V coordinates for texture N are offset by N * atlasSlotHeight
 * - atlasSlotHeight = resolution.height from the .bbmodel
 *
 * Skips meta-bones: b_*, hitbox*, tag_*, m_*
 */
public class BedrockGeometryGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<String> SKIP_PREFIXES = List.of("b_", "hitbox", "tag_", "m_");

    /**
     * @param modelId          Model identifier
     * @param bbmodel          Parsed .bbmodel JSON
     * @param uvWidth          UV space width (from .bbmodel resolution)
     * @param uvHeight         UV space height per texture slot (from .bbmodel resolution)
     * @param textureCount     Number of textures in the model
     * @param atlasSlotWidth   Actual atlas slot width (native texture resolution)
     * @param atlasSlotHeight  Actual atlas slot height (native texture resolution)
     */
    public static String generate(String modelId, Map<?, ?> bbmodel, double uvWidth, double uvHeight, int textureCount,
                                   int atlasSlotWidth, int atlasSlotHeight) {
        // UV scale factors: map from UV space to actual atlas pixel space
        double uScale = atlasSlotWidth / uvWidth;
        double vScale = atlasSlotHeight / uvHeight;
        double atlasHeight = (double) atlasSlotHeight * textureCount;

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
            traverseOutliner(outliner, null, elementMap, bones, uScale, vScale, atlasSlotHeight);
        }

        // Calculate visible_bounds from actual cube coordinates
        double[] bounds = calculateBounds(bones);

        // Build geometry JSON
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");

        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.fmmbridge." + modelId);
        description.addProperty("texture_width", atlasSlotWidth);
        description.addProperty("texture_height", (int) atlasHeight);
        // visible_bounds in blocks (pixels / 16), with padding
        double boundsWidth = Math.max(bounds[1] - bounds[0], bounds[5] - bounds[4]) / 16.0 + 2;
        double boundsHeight = (bounds[3] - bounds[2]) / 16.0 + 2;
        double boundsOffsetY = (bounds[2] + bounds[3]) / 2.0 / 16.0;
        description.addProperty("visible_bounds_width", round(Math.max(boundsWidth, 4)));
        description.addProperty("visible_bounds_height", round(Math.max(boundsHeight, 4)));
        description.add("visible_bounds_offset", toJsonArray(0.0, round(boundsOffsetY), 0.0));

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
                                          double uScale, double vScale, int atlasSlotHeight) {
        for (Object item : items) {
            if (item instanceof Map<?, ?> group) {
                String boneName = (String) group.get("name");
                if (boneName == null) continue;
                if (shouldSkip(boneName)) continue;

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

                // Bone rotation
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
                    for (Object child : children) {
                        if (child instanceof String uuid) {
                            Map<?, ?> element = elementMap.get(uuid);
                            if (element != null) {
                                String type = (String) element.get("type");
                                if (!"locator".equals(type) && !"null_object".equals(type)) {
                                    JsonObject cube = buildCube(element, uScale, vScale, atlasSlotHeight);
                                    if (cube != null) cubesArray.add(cube);
                                }
                            }
                        }
                    }
                    if (cubesArray.size() > 0) bone.add("cubes", cubesArray);

                    bones.add(bone);

                    // Recurse into child bone groups
                    traverseOutliner(children, safeName, elementMap, bones, uScale, vScale, atlasSlotHeight);
                } else {
                    bones.add(bone);
                }
            }
        }
    }

    private static JsonObject buildCube(Map<?, ?> element, double uScale, double vScale, int atlasSlotHeight) {
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

        double sizeX = toX - fromX;
        double sizeY = toY - fromY;
        double sizeZ = toZ - fromZ;

        JsonObject cube = new JsonObject();
        cube.add("origin", toJsonArray(fromX, fromY, fromZ));
        cube.add("size", toJsonArray(sizeX, sizeY, sizeZ));

        // Cube pivot + rotation
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

        // UV mapping: per-face with texture atlas offset
        Map<?, ?> faces = (Map<?, ?>) element.get("faces");
        if (faces != null) {
            JsonObject uvObj = new JsonObject();
            for (String face : new String[]{"north", "south", "east", "west", "up", "down"}) {
                Map<?, ?> faceData = (Map<?, ?>) faces.get(face);
                if (faceData == null) continue;
                List<?> uv = (List<?>) faceData.get("uv");
                if (uv == null || uv.size() < 4) continue;

                // Skip faces without texture assignment
                Object texRef = faceData.get("texture");
                if (texRef == null) continue;

                // Get texture index for this face (default 0)
                int textureIndex = 0;
                if (texRef instanceof Number) {
                    textureIndex = ((Number) texRef).intValue();
                }

                double u1 = toDouble(uv.get(0));
                double v1 = toDouble(uv.get(1));
                double u2 = toDouble(uv.get(2));
                double v2 = toDouble(uv.get(3));

                // Scale UV from .bbmodel UV space to actual atlas pixel space
                // Round to integer pixel boundaries to prevent seam artifacts
                double scaledU1 = Math.round(u1 * uScale);
                double scaledV1 = Math.round(v1 * vScale);
                double scaledU2 = Math.round(u2 * uScale);
                double scaledV2 = Math.round(v2 * vScale);

                // Offset V coordinate by texture slot position in atlas
                double vOffset = textureIndex * atlasSlotHeight;

                JsonObject faceUV = new JsonObject();
                faceUV.add("uv", toJsonArray(scaledU1, scaledV1 + vOffset));
                faceUV.add("uv_size", toJsonArray(scaledU2 - scaledU1, scaledV2 - scaledV1));
                uvObj.add(face, faceUV);
            }
            cube.add("uv", uvObj);
        }

        return cube;
    }

    /**
     * Calculates bounding box from all cubes across all bones.
     * @return [minX, maxX, minY, maxY, minZ, maxZ]
     */
    private static double[] calculateBounds(List<JsonObject> bones) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        boolean found = false;

        for (JsonObject bone : bones) {
            if (!bone.has("cubes")) continue;
            for (var cubeElem : bone.getAsJsonArray("cubes")) {
                JsonObject cube = cubeElem.getAsJsonObject();
                JsonArray origin = cube.getAsJsonArray("origin");
                JsonArray size = cube.getAsJsonArray("size");
                if (origin == null || size == null) continue;

                double ox = origin.get(0).getAsDouble();
                double oy = origin.get(1).getAsDouble();
                double oz = origin.get(2).getAsDouble();
                double sx = size.get(0).getAsDouble();
                double sy = size.get(1).getAsDouble();
                double sz = size.get(2).getAsDouble();

                minX = Math.min(minX, ox);
                maxX = Math.max(maxX, ox + sx);
                minY = Math.min(minY, oy);
                maxY = Math.max(maxY, oy + sy);
                minZ = Math.min(minZ, oz);
                maxZ = Math.max(maxZ, oz + sz);
                found = true;
            }
        }

        if (!found) return new double[]{-16, 16, 0, 32, -16, 16};
        return new double[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    private static boolean shouldSkip(String boneName) {
        String lower = boneName.toLowerCase();
        for (String prefix : SKIP_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String safeBoneName(String name) {
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
