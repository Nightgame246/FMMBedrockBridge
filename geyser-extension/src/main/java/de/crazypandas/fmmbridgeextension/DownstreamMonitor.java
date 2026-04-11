package de.crazypandas.fmmbridgeextension;

import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.extension.Extension;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Periodically re-registers GeyserUtils downstream packet listener for each session.
 * Required in multi-server setups (Hub → Backend) where the listener is lost on server switch.
 */
public class DownstreamMonitor {

    private final Extension extension;
    private final Map<GeyserConnection, Long> lastRegistrationTime = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;
    private Method registerPacketListenerMethod;
    private Object geyserUtilsInstance;

    public DownstreamMonitor(Extension extension) {
        this.extension = extension;
    }

    public boolean init() {
        findGeyserUtils();
        if (geyserUtilsInstance == null || registerPacketListenerMethod == null) {
            extension.logger().warning("DownstreamMonitor: GeyserUtils not found — disabled");
            return false;
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FMMBridge-DownstreamMonitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::checkAll, 2, 5, TimeUnit.SECONDS);
        extension.logger().info("DownstreamMonitor: Started (every 5s)");
        return true;
    }

    public void onSessionDisconnect(GeyserConnection connection) {
        lastRegistrationTime.remove(connection);
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        lastRegistrationTime.clear();
    }

    private void findGeyserUtils() {
        try {
            for (Extension ext : GeyserApi.api().extensionManager().extensions()) {
                if (ext.getClass().getName().equals("me.zimzaza4.geyserutils.geyser.GeyserUtils")) {
                    geyserUtilsInstance = ext;
                    extension.logger().info("DownstreamMonitor: Found GeyserUtils instance");
                    break;
                }
            }

            if (geyserUtilsInstance == null) return;

            for (Method m : geyserUtilsInstance.getClass().getDeclaredMethods()) {
                if (m.getName().equals("registerPacketListener") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    registerPacketListenerMethod = m;
                    break;
                }
            }
        } catch (Exception e) {
            extension.logger().error("DownstreamMonitor: Init failed: " + e.getMessage(), e);
        }
    }

    private void checkAll() {
        if (registerPacketListenerMethod == null) return;
        for (GeyserConnection connection : GeyserApi.api().onlineConnections()) {
            try {
                checkConnection(connection);
            } catch (Exception e) {
                extension.logger().error("DownstreamMonitor: Error for " + connection + ": " + e.getMessage(), e);
            }
        }
    }

    private void checkConnection(GeyserConnection connection) throws Exception {
        long now = System.currentTimeMillis();
        Long lastTime = lastRegistrationTime.get(connection);
        if (lastTime != null && (now - lastTime) < 30_000) return;

        lastRegistrationTime.put(connection, now);
        registerPacketListenerMethod.invoke(geyserUtilsInstance, connection);
        sendChannelRegistration(connection);
    }

    private void sendChannelRegistration(GeyserConnection connection) {
        try {
            Method getDownstream = connection.getClass().getMethod("getDownstream");
            getDownstream.setAccessible(true);
            Object downstream = getDownstream.invoke(connection);
            if (downstream == null) return;

            Method getSession = downstream.getClass().getMethod("getSession");
            getSession.setAccessible(true);
            Object tcpSession = getSession.invoke(downstream);
            if (tcpSession == null) return;

            byte[] channelBytes = "geyserutils:main".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Class<?> payloadClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket",
                    true, connection.getClass().getClassLoader());

            Object packet = null;
            for (var constructor : payloadClass.getConstructors()) {
                var params = constructor.getParameterTypes();
                if (params.length == 2) {
                    if (params[0] == String.class && params[1] == byte[].class) {
                        packet = constructor.newInstance("minecraft:register", channelBytes);
                        break;
                    }
                    if (params[0].getSimpleName().contains("Key") && params[1] == byte[].class) {
                        Method ofMethod = params[0].getMethod("key", String.class);
                        Object key = ofMethod.invoke(null, "minecraft:register");
                        packet = constructor.newInstance(key, channelBytes);
                        break;
                    }
                }
            }

            if (packet == null) return;

            Method sendMethod = tcpSession.getClass().getMethod("send",
                    Class.forName("org.geysermc.mcprotocollib.network.packet.Packet",
                            true, connection.getClass().getClassLoader()));
            sendMethod.setAccessible(true);
            sendMethod.invoke(tcpSession, packet);
        } catch (Exception e) {
            extension.logger().warning("DownstreamMonitor: Channel registration failed: " + e.getMessage());
        }
    }
}
