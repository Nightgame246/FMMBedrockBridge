package de.crazypandas.fmmbedrockbridge.converter;

import com.google.gson.*;

import java.util.*;

/**
 * Generates Bedrock animation controller (.animation_controllers.json).
 *
 * Uses the same bitmask approach as GeyserModelEngine:
 * - Each animation gets a bit position (2^N) within a property
 * - Up to 24 animations per property (anim0, anim1, ...)
 * - Controller checks: math.mod(math.floor(query.property('fmmbridge:anim0') / 2^N), 2) != 0
 * - To play animation N, set bit N in the property value
 *
 * The backend sends property updates via GeyserUtils when animation state changes.
 */
public class BedrockAnimationControllerGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String NAMESPACE = "fmmbridge";

    /**
     * Generates an animation controller JSON for all animations of a model.
     *
     * @param modelId        Model identifier
     * @param animationNames List of animation names (already sanitized/lowercase)
     * @return JSON string of the animation controller file, or null if no animations
     */
    public static String generate(String modelId, List<String> animationNames) {
        if (animationNames == null || animationNames.isEmpty()) return null;

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");

        JsonObject controllers = new JsonObject();

        List<String> sorted = new ArrayList<>(animationNames);
        Collections.sort(sorted);

        for (int i = 0; i < sorted.size(); i++) {
            String animName = sorted.get(i);
            String animId = "animation.fmmbridge." + modelId + "." + animName;
            String controllerId = "controller.animation.fmmbridge." + modelId + "." + animName;

            int propertyIndex = i / 24;
            int bitPosition = i % 24;
            int bitmask = (int) Math.pow(2, bitPosition);

            String query = "math.mod(math.floor(query.property('" + NAMESPACE + ":anim" + propertyIndex + "') / " + bitmask + "), 2)";

            JsonObject controller = createController(animId, query);
            controllers.add(controllerId, controller);
        }

        root.add("animation_controllers", controllers);
        return GSON.toJson(root);
    }

    /**
     * Returns the number of property slots needed for the given animation count.
     * Each slot holds up to 24 animations.
     */
    public static int getPropertySlotCount(int animationCount) {
        return Math.max(1, (animationCount + 23) / 24);
    }

    /**
     * Calculates the property value to play a specific animation by index.
     * @param animIndex Index in the sorted animation list
     * @return [propertyIndex, bitmaskValue]
     */
    public static int[] getAnimationBitmask(int animIndex) {
        int propertyIndex = animIndex / 24;
        int bitPosition = animIndex % 24;
        int bitmask = (int) Math.pow(2, bitPosition);
        return new int[]{propertyIndex, bitmask};
    }

    private static JsonObject createController(String animationId, String query) {
        JsonObject controller = new JsonObject();
        controller.addProperty("initial_state", "stop");

        JsonObject states = new JsonObject();

        // Play state
        JsonObject playState = new JsonObject();
        JsonArray playAnims = new JsonArray();
        playAnims.add(animationId);
        playState.add("animations", playAnims);
        playState.addProperty("blend_transition", 0.1);
        JsonArray playTransitions = new JsonArray();
        JsonObject toStop = new JsonObject();
        toStop.addProperty("stop", query + " == 0");
        playTransitions.add(toStop);
        playState.add("transitions", playTransitions);

        // Stop state
        JsonObject stopState = new JsonObject();
        stopState.addProperty("blend_transition", 0.1);
        JsonArray stopTransitions = new JsonArray();
        JsonObject toPlay = new JsonObject();
        toPlay.addProperty("play", query + " != 0");
        stopTransitions.add(toPlay);
        stopState.add("transitions", stopTransitions);

        states.add("play", playState);
        states.add("stop", stopState);
        controller.add("states", states);

        return controller;
    }
}
