---
name: plugin-development
description: "Use when building, modifying, or debugging Spigot/Paper/Velocity/BungeeCord plugins — covers project setup, architecture patterns, async threading, NMS, event listeners, commands, Vault, LuckPerms, PlaceholderAPI"
---

# Skill: Minecraft Plugin Development

## Scope
Building, modifying, and debugging plugins for Spigot, Paper, Velocity, BungeeCord, and their forks.

## Project Setup

### Paper/Spigot Plugin
```xml
<!-- pom.xml essentials -->
<repository>
    <id>papermc</id>
    <url>https://repo.papermc.io/repository/maven-public/</url>
</repository>
<dependency>
    <groupId>io.papermc.paper</groupId>
    <artifactId>paper-api</artifactId>
    <version>1.21.4-R0.1-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### Velocity Plugin
```xml
<dependency>
    <groupId>com.velocitypowered</groupId>
    <artifactId>velocity-api</artifactId>
    <version>3.4.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### plugin.yml (Bukkit/Paper)
```yaml
name: PluginName
version: ${project.version}
main: com.example.plugin.MainClass
api-version: '1.21'
depend: [RequiredPlugin]
softdepend: [OptionalPlugin]
```

### Velocity Plugin Annotation
```java
@Plugin(id = "pluginname", name = "PluginName", version = "1.0.0",
        dependencies = {@Dependency(id = "requiredplugin")})
public class MainClass { }
```

## Architecture Patterns

### Main Class Pattern
```java
public class MyPlugin extends JavaPlugin {
    private static MyPlugin instance;
    
    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        // Register listeners, commands, managers
        getServer().getPluginManager().registerEvents(new MyListener(), this);
    }
    
    @Override
    public void onDisable() {
        // Cleanup: close databases, cancel tasks, save data
    }
    
    public static MyPlugin getInstance() { return instance; }
}
```

### Listener Pattern
```java
public class MyListener implements Listener {
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEvent(SomeEvent event) {
        // Handle event
    }
}
```

### Async Database Operations
```java
// NEVER block the main thread with database calls
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    // Database operation here
    Object result = database.query(...);
    
    // Switch back to main thread for Bukkit API calls
    Bukkit.getScheduler().runTask(plugin, () -> {
        player.sendMessage("Result: " + result);
    });
});
```

## Critical Rules

### Thread Safety
- **Main Thread Only:** All Bukkit API calls (world manipulation, entity spawning, player interaction) MUST happen on the main server thread
- **Async Safe:** Database queries, HTTP requests, file I/O should be async
- **BukkitScheduler:** Use `runTask()` for main thread, `runTaskAsynchronously()` for async
- **Paper:** Prefer `Bukkit.getGlobalRegionScheduler()` on Folia-compatible plugins

### Dependencies
- **Never shade the API** — use `provided` scope for server APIs
- **Shade libraries** that aren't available at runtime (database drivers, utility libs)
- **Relocate shaded packages** to avoid conflicts: `com.example.libs.hikari`

### NMS (Net.Minecraft.Server)
- **Avoid NMS when possible** — use Paper API or ProtocolLib instead
- NMS breaks between EVERY Minecraft version
- If NMS is required, use reflection or multi-version abstraction layers
- Paper has `paper-api` methods that replace many common NMS use cases

### Version Compatibility
- Test on the target version AND one version below
- Use `api-version` in plugin.yml to set minimum version
- ViaVersion does NOT make plugins compatible — it translates protocol, not API

### Resource Management
- Always close database connections in `onDisable()`
- Cancel all BukkitTasks in `onDisable()`
- Unregister listeners if needed (usually automatic)
- Close file handles and streams

## Common APIs & Hooks

### PlaceholderAPI
```java
// Register expansion
if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
    new MyExpansion().register();
}
```

### Vault (Economy/Permissions)
```java
RegisteredServiceProvider<Economy> rsp = 
    getServer().getServicesManager().getRegistration(Economy.class);
Economy economy = rsp.getProvider();
```

### LuckPerms
```java
LuckPerms api = LuckPermsProvider.get();
User user = api.getUserManager().loadUser(uuid).join();
```

### Plugin Messaging (Proxy ↔ Backend)
```java
// Register channel
getServer().getMessenger().registerOutgoingPluginChannel(this, "myplugin:channel");
getServer().getMessenger().registerIncomingPluginChannel(this, "myplugin:channel", this);

// Send message
ByteArrayOutputStream b = new ByteArrayOutputStream();
DataOutputStream out = new DataOutputStream(b);
out.writeUTF("message");
player.sendPluginMessage(this, "myplugin:channel", b.toByteArray());
```

## Testing

### Local Test Server
1. Build with `mvn clean package`
2. Copy JAR to `plugins/` folder of a test server
3. Start server, check console for errors
4. Test all commands and features manually

### Unit Testing (MockBukkit)
```xml
<dependency>
    <groupId>com.github.seeseemelk</groupId>
    <artifactId>MockBukkit-v1.21</artifactId>
    <version>4.0.0</version>
    <scope>test</scope>
</dependency>
```

## Debugging Checklist
1. Check server console for stack traces
2. Verify plugin loaded: `/plugins` command
3. Check dependency load order in startup log
4. Verify correct API version in plugin.yml
5. Test with only your plugin + dependencies installed
6. Check for conflicting event handlers
7. Verify async/sync threading is correct
