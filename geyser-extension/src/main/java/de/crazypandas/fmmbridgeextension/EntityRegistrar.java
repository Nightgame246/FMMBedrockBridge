package de.crazypandas.fmmbridgeextension;

import org.geysermc.geyser.api.extension.Extension;

import java.lang.reflect.Method;

/**
 * Registers custom entities and their properties with GeyserUtils via reflection.
 */
public class EntityRegistrar {

    private final Extension extension;

    public EntityRegistrar(Extension extension) {
        this.extension = extension;
    }

    /**
     * Registers a custom entity with GeyserUtils via reflection.
     * Tries multiple classloader strategies.
     */
    public boolean registerEntity(String modelId) {
        String fullId = "fmmbridge:" + modelId;
        extension.logger().info("EntityRegistrar: Registering " + fullId);

        ClassLoader[] classLoaders = {
            extension.getClass().getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        };

        for (ClassLoader cl : classLoaders) {
            if (cl == null) continue;
            try {
                Class<?> geyserUtilsClass = Class.forName(
                        "me.zimzaza4.geyserutils.geyser.GeyserUtils", true, cl);
                Method addCustomEntity = geyserUtilsClass.getMethod("addCustomEntity", String.class);
                addCustomEntity.invoke(null, fullId);
                extension.logger().info("EntityRegistrar: Registered " + fullId + " via " + cl);
                return true;
            } catch (ClassNotFoundException e) {
                // Try next classloader
            } catch (Exception e) {
                extension.logger().warning("EntityRegistrar: Failed via " + cl + ": " + e.getMessage());
            }
        }

        extension.logger().warning("EntityRegistrar: All classloader strategies failed for " + fullId);
        return false;
    }

    /**
     * Registers animation properties for a model.
     * Must be called AFTER registerEntity() and during GeyserPreInitializeEvent.
     */
    public void registerAnimationProperties(String modelId, int animationCount) {
        if (animationCount == 0) return;

        String fullId = "fmmbridge:" + modelId;
        int slotCount = (int) Math.ceil(animationCount / 24.0);

        try {
            Class<?> geyserUtilsClass = Class.forName(
                    "me.zimzaza4.geyserutils.geyser.GeyserUtils", true,
                    extension.getClass().getClassLoader());
            Method addProperty = geyserUtilsClass.getMethod(
                    "addProperty", String.class, String.class, Class.class);
            Method registerProps = geyserUtilsClass.getMethod(
                    "registerProperties", String.class);

            for (int i = 0; i < slotCount; i++) {
                addProperty.invoke(null, fullId, "fmmbridge:anim" + i, Integer.class);
            }
            registerProps.invoke(null, fullId);

            extension.logger().info("EntityRegistrar: Registered " + slotCount
                    + " animation property slots for " + fullId);
        } catch (Exception e) {
            extension.logger().warning("EntityRegistrar: Failed to register animation properties for "
                    + fullId + ": " + e.getMessage());
        }
    }
}
