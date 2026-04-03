package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Tracks the current animation state of FMM entities via reflection.
 *
 * Reflection chain:
 *   ModeledEntity.animationComponent → AnimationComponent.animationManager → AnimationManager.current → IAnimState.getType()
 *
 * Returns the animation state name (idle, walk, attack, death, spawn, or custom animation name).
 */
public class AnimationStateTracker {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    // Reflection fields (resolved once)
    private static Field animationComponentField;
    private static Field animationManagerField;
    private static Field currentStateField;
    private static Method getTypeMethod;
    private static boolean reflectionInitialized = false;
    private static boolean reflectionFailed = false;

    /**
     * Gets the current animation state name for a ModeledEntity.
     *
     * @return animation name (e.g. "idle", "walk", "attack", "death") or null if unavailable
     */
    public static String getCurrentAnimationName(ModeledEntity entity) {
        if (reflectionFailed) return null;
        if (!reflectionInitialized) initReflection();
        if (reflectionFailed) return null;

        try {
            // ModeledEntity → animationComponent
            Object animComponent = animationComponentField.get(entity);
            if (animComponent == null) return null;

            // AnimationComponent → animationManager
            Object animManager = animationManagerField.get(animComponent);
            if (animManager == null) return null;

            // AnimationManager → current (IAnimState)
            Object currentState = currentStateField.get(animManager);
            if (currentState == null) return null;

            // IAnimState.getType() → AnimationStateType enum
            Object stateType = getTypeMethod.invoke(currentState);
            if (stateType == null) return null;

            String typeName = stateType.toString().toLowerCase();

            // For CUSTOM state, try to get the animation name
            if ("custom".equals(typeName)) {
                return getCustomAnimationName(currentState);
            }

            return typeName;
        } catch (Exception e) {
            // Don't spam logs — just return null
            return null;
        }
    }

    /**
     * Tries to extract the custom animation name from a CustomAnimationState.
     */
    private static String getCustomAnimationName(Object customState) {
        try {
            // CustomAnimationState has an Animation field, which has a name
            // Try to find the animation field
            for (Field f : customState.getClass().getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("Animation")) {
                    f.setAccessible(true);
                    Object animation = f.get(customState);
                    if (animation != null) {
                        // Animation might have a getName() or name field
                        try {
                            Method getName = animation.getClass().getMethod("getName");
                            getName.setAccessible(true);
                            Object name = getName.invoke(animation);
                            if (name != null) return name.toString().toLowerCase();
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return "custom";
    }

    private static synchronized void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        try {
            // ModeledEntity.animationComponent
            animationComponentField = ModeledEntity.class.getDeclaredField("animationComponent");
            animationComponentField.setAccessible(true);

            // AnimationComponent.animationManager
            Class<?> animComponentClass = animationComponentField.getType();
            animationManagerField = animComponentClass.getDeclaredField("animationManager");
            animationManagerField.setAccessible(true);

            // AnimationManager.current
            Class<?> animManagerClass = animationManagerField.getType();
            currentStateField = animManagerClass.getDeclaredField("current");
            currentStateField.setAccessible(true);

            // IAnimState.getType() — find via interface
            Class<?> stateInterface = currentStateField.getType();
            getTypeMethod = stateInterface.getMethod("getType");
            getTypeMethod.setAccessible(true);

            log.info("[BRIDGE] AnimationStateTracker reflection initialized successfully");
        } catch (Exception e) {
            reflectionFailed = true;
            log.warning("[BRIDGE] AnimationStateTracker reflection failed: " + e.getMessage()
                    + " — animation sync disabled");
        }
    }
}
