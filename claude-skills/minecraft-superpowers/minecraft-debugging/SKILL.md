---
name: minecraft-debugging
description: "Use when diagnosing server crashes, plugin errors, stack traces, TPS lag, packet errors, ClassNotFoundException, version incompatibility, Geyser/Bedrock issues, proxy problems, or plugin conflicts"
---

# Skill: Minecraft Server & Plugin Debugging

## Scope
Diagnosing and fixing crashes, errors, performance issues, and compatibility problems on Minecraft servers.

## Debugging Methodology

### Phase 1: Gather Evidence (DON'T FIX YET)
1. Get the FULL stack trace or error log
2. Identify WHEN the error occurs (startup, player join, specific action)
3. Identify WHAT changed recently (plugin update, MC version change, config change)
4. Check if the error is reproducible

### Phase 2: Analyze
1. Read the stack trace bottom-to-top for root cause
2. Identify which plugin/mod owns the failing class
3. Check version compatibility
4. Search for known issues (GitHub issues, SpigotMC, Modrinth)

### Phase 3: Isolate
1. Test with only the problematic plugin + its dependencies
2. Check if the issue exists on a clean server
3. Binary search: disable half the plugins, narrow down

### Phase 4: Fix
1. Apply the fix
2. Verify the fix resolves the issue
3. Verify the fix doesn't break other things
4. Document what was wrong and how it was fixed

## Common Error Patterns

### ClassNotFoundException / NoClassDefFoundError
```
java.lang.ClassNotFoundException: com.example.SomeClass
```
**Causes:**
- Missing dependency plugin
- Wrong plugin version for MC version
- Plugin compiled against different API version
- Shading issue — library not included in JAR

**Fix:** Check plugin dependencies, verify versions match

### NoSuchMethodError / NoSuchFieldError
```
java.lang.NoSuchMethodError: 'void org.bukkit.Something.method()'
```
**Causes:**
- Plugin compiled against newer/older API than server
- NMS method changed between versions
- Library version mismatch

**Fix:** Update plugin or server to matching versions

### PacketEvents / Protocol Errors
```
PacketProcessException: Failed to map Packet ID X to PacketType
Can't resolve 'minecraft:something' in 'minecraft:registry' for V_X_XX
```
**Causes:**
- PacketEvents version doesn't support server MC version
- ViaVersion introducing unknown packets
- Incompatible protocol library version

**Fix:** Update PacketEvents, or remove if no plugin actually needs it

### Player Disconnect: "Internal Error"
```
[connected player] Name has disconnected: Bei deiner Verbindung ist ein interner Fehler aufgetreten
```
**Causes:**
- Plugin throwing exception during player join/config phase
- Packet processing error (PacketEvents, ProtocolLib, ViaVersion)
- Invalid packet data from backend server

**Fix:** Check proxy logs for the actual exception preceding the disconnect

### Database Connection Issues
```
HikariPool - Connection is not available
Communications link failure
```
**Causes:**
- Database server down or unreachable
- Max connections exceeded
- Connection timeout too short
- Wrong credentials

**Fix:** Check DB server, increase pool size, verify credentials

### TPS Lag / Performance
```
[Server thread/WARN]: Can't keep up! Is the server overloaded?
```
**Investigation:**
1. Use `/spark profiler` to get CPU profile
2. Check `/spark tps` for TPS history
3. Check `/spark gc` for garbage collection issues
4. Look for plugins doing sync operations that should be async

## Version-Specific Issues

### ViaVersion + New MC Versions
ViaVersion translates protocol but can't handle:
- New registry entries unknown to older protocol
- Changed packet structures
- New game mechanics not present in older versions

### Geyser/Bedrock Issues
- Bedrock clients ALWAYS on latest version — can't choose
- Geyser must match Bedrock version
- ViaVersion must support backend server version
- Floodgate versions must match between proxy and backend

### Plugin Load Order
Plugins load in dependency order. Common issues:
- Plugin loads before its dependency → add to `depend:` in plugin.yml
- Soft dependency not available at load time → null checks needed
- Multiple plugins modifying same event at same priority → conflicts

## Log Analysis Shortcuts

```bash
# Find all errors/exceptions
grep -i "error\|exception\|warn" logs/latest.log | grep -v "can safely ignore"

# Find specific plugin issues
grep -i "pluginname" logs/latest.log

# Find player connection issues
grep -i "connected\|disconnected\|kicked" logs/latest.log

# Find performance warnings
grep -i "can't keep up\|tps\|lag" logs/latest.log

# Find dependency issues
grep -i "depend\|missing\|not found\|ClassNotFound" logs/latest.log

# Find startup errors (first 100 lines after boot)
head -200 logs/latest.log | grep -i "error\|exception\|fail"
```

## Plugin Conflict Resolution

### Common Conflicting Plugin Types
- **Packet Libraries:** PacketEvents vs ProtocolLib — pick one if possible
- **Permission Plugins:** Multiple permission plugins = chaos
- **Chat Plugins:** VentureChat, EssentialsChat, CMI — only use one
- **Anti-Cheat + Protocol:** Anti-cheats that use packets may conflict with Geyser
- **World Management:** Multiple world plugins can conflict

### Resolution Steps
1. Identify which plugins touch the same system
2. Check their documentation for known incompatibilities
3. Test disabling one at a time
4. Check event priorities — plugins may override each other's handlers
5. Contact plugin developers with specific reproduction steps

## Proxy-Specific Debugging

### Velocity
```bash
# Check registered servers
grep "server" velocity.toml

# Check plugin loading
grep -i "loaded plugin" logs/latest.log

# Check player routing
grep -i "connected\|server connection" logs/latest.log
```

### Common Proxy Issues
- **Transfer packet:** Some plugins break server switching
- **Plugin messaging:** Channel registration must happen on both sides
- **Forwarding mode:** modern forwarding requires matching secret on backend
- **Read timeout:** Backend server taking too long to respond
