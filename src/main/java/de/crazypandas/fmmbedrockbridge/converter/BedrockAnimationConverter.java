package de.crazypandas.fmmbedrockbridge.converter;

import com.google.gson.*;

import java.util.*;

/**
 * Converts .bbmodel animations to Bedrock .animation.json format.
 *
 * .bbmodel format:
 *   animations[]: { name, loop, length, animators: { boneKey: { name, type, keyframes[] } } }
 *   keyframe: { channel: "rotation"|"position"|"scale", time, interpolation, data_points: [{x,y,z}] }
 *
 * Bedrock format:
 *   animations: { "animation.id.name": { loop, animation_length, bones: { boneName: { rotation: {time: [x,y,z]}, ... } } } }
 */
public class BedrockAnimationConverter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // .bbmodel position keyframes are in the same coordinate space as the geometry — used directly.

    /**
     * Converts all animations from a .bbmodel to Bedrock .animation.json.
     *
     * @param modelId   Model identifier (used in animation IDs)
     * @param bbmodel   Parsed .bbmodel JSON
     * @return JSON string of the Bedrock animation file, or null if no animations
     */
    public static String convert(String modelId, Map<?, ?> bbmodel) {
        List<?> animations = (List<?>) bbmodel.get("animations");
        if (animations == null || animations.isEmpty()) return null;

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");

        JsonObject animationsObj = new JsonObject();

        for (Object animObj : animations) {
            Map<?, ?> anim = (Map<?, ?>) animObj;
            String name = (String) anim.get("name");
            if (name == null) continue;

            String safeName = name.replace(" ", "_").toLowerCase(Locale.ROOT);
            String animId = "animation.fmmbridge." + modelId + "." + safeName;

            JsonObject bedrockAnim = convertAnimation(anim);
            if (bedrockAnim != null) {
                animationsObj.add(animId, bedrockAnim);
            }
        }

        if (animationsObj.size() == 0) return null;

        root.add("animations", animationsObj);
        return GSON.toJson(root);
    }

    /**
     * Returns the list of animation names found in the .bbmodel.
     */
    public static List<String> getAnimationNames(Map<?, ?> bbmodel) {
        List<String> names = new ArrayList<>();
        List<?> animations = (List<?>) bbmodel.get("animations");
        if (animations == null) return names;

        for (Object animObj : animations) {
            Map<?, ?> anim = (Map<?, ?>) animObj;
            String name = (String) anim.get("name");
            if (name != null) {
                names.add(name.replace(" ", "_").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static JsonObject convertAnimation(Map<?, ?> anim) {
        String name = (String) anim.get("name");
        Object loopObj = anim.get("loop");
        double length = toDouble(anim.get("length"));

        JsonObject result = new JsonObject();

        // Loop mode: "loop" → true, "once"/false → false, "hold_on_last_frame" → "hold_on_last_frame"
        if (loopObj instanceof String loopStr) {
            switch (loopStr) {
                case "loop" -> result.addProperty("loop", true);
                case "hold_on_last_frame" -> result.addProperty("loop", "hold_on_last_frame");
                default -> result.addProperty("loop", false);
            }
        } else if (loopObj instanceof Boolean b) {
            result.addProperty("loop", b);
        } else {
            result.addProperty("loop", false);
        }

        result.addProperty("animation_length", round(length));

        // Convert animators (bones) to Bedrock format
        Map<?, ?> animators = (Map<?, ?>) anim.get("animators");
        if (animators == null || animators.isEmpty()) return result;

        JsonObject bonesObj = new JsonObject();

        for (Object entry : animators.values()) {
            Map<?, ?> animator = (Map<?, ?>) entry;
            String boneName = (String) animator.get("name");
            String type = (String) animator.get("type");
            if (boneName == null) continue;
            // Skip non-bone animators (effects, etc.) but include null_objects for now
            if (type != null && !type.equals("bone") && !type.equals("null_object")) continue;

            String safeBoneName = safeBoneName(boneName);

            List<?> keyframes = (List<?>) animator.get("keyframes");
            if (keyframes == null || keyframes.isEmpty()) continue;

            JsonObject boneAnim = convertBoneKeyframes(keyframes);
            if (boneAnim != null && boneAnim.size() > 0) {
                bonesObj.add(safeBoneName, boneAnim);
            }
        }

        if (bonesObj.size() > 0) {
            result.add("bones", bonesObj);
        }

        return result;
    }

    /**
     * Groups keyframes by channel (rotation/position/scale) and converts to Bedrock timeline format.
     */
    private static JsonObject convertBoneKeyframes(List<?> keyframes) {
        // Group by channel
        Map<String, List<Map<?, ?>>> byChannel = new LinkedHashMap<>();
        for (Object kfObj : keyframes) {
            Map<?, ?> kf = (Map<?, ?>) kfObj;
            String channel = (String) kf.get("channel");
            if (channel == null) continue;
            byChannel.computeIfAbsent(channel, k -> new ArrayList<>()).add(kf);
        }

        JsonObject boneAnim = new JsonObject();

        for (Map.Entry<String, List<Map<?, ?>>> entry : byChannel.entrySet()) {
            String channel = entry.getKey();
            List<Map<?, ?>> channelKeyframes = entry.getValue();

            // Sort by time
            channelKeyframes.sort(Comparator.comparingDouble(kf -> toDouble(kf.get("time"))));

            JsonObject timeline = convertTimeline(channelKeyframes);
            if (timeline != null && timeline.size() > 0) {
                boneAnim.add(channel, timeline);
            }
        }

        return boneAnim;
    }

    /**
     * Converts a sorted list of keyframes for one channel to a Bedrock timeline object.
     * Format: { "0.0": [x, y, z], "0.5": [x, y, z] } or with lerp_mode.
     */
    private static JsonObject convertTimeline(List<Map<?, ?>> keyframes) {
        JsonObject timeline = new JsonObject();

        for (Map<?, ?> kf : keyframes) {
            double time = toDouble(kf.get("time"));
            String interpolation = (String) kf.get("interpolation");
            List<?> dataPoints = (List<?>) kf.get("data_points");
            if (dataPoints == null || dataPoints.isEmpty()) continue;

            Map<?, ?> point = (Map<?, ?>) dataPoints.get(0);
            double x = toDouble(point.get("x"));
            double y = toDouble(point.get("y"));
            double z = toDouble(point.get("z"));


            String timeKey = formatTime(time);

            if (interpolation != null && interpolation.equals("catmullrom")) {
                // Catmullrom needs explicit lerp_mode
                JsonObject kfObj = new JsonObject();
                kfObj.add("post", toJsonArray(x, y, z));
                kfObj.addProperty("lerp_mode", "catmullrom");
                timeline.add(timeKey, kfObj);
            } else {
                // Linear (default), step, bezier → use simple array format
                timeline.add(timeKey, toJsonArray(x, y, z));
            }
        }

        return timeline;
    }

    /**
     * Must match the renaming rule in BedrockGeometryGenerator.safeBoneName so animation
     * keyframes target the same bone names that appear in the geometry. Any bone whose name
     * contains "head" is renamed (head → noggin) to escape Bedrock's hardcoded head_yaw
     * auto-rotation that triggers on substring match.
     */
    private static String safeBoneName(String name) {
        String safe = name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        if (safe.contains("head")) return safe.replace("head", "noggin");
        return safe;
    }

    private static String formatTime(double time) {
        // Use minimal precision: "0" for 0.0, "0.5" for 0.5, etc.
        if (time == (int) time) {
            return String.valueOf((int) time);
        }
        // Round to 4 decimal places and strip trailing zeros
        String s = String.format(Locale.US, "%.4f", time).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static JsonArray toJsonArray(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(round(x));
        a.add(round(y));
        a.add(round(z));
        return a;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
