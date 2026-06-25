---
name: minecraft-server-admin
description: "Use when configuring Minecraft servers — covers Velocity/Paper/Spigot config, JVM flags, performance tuning, database setup, backup strategies, AMP panel, firewall, SSH hardening, monitoring with Spark"
---

# Skill: Minecraft Server Administration

## Scope
Server setup, configuration, performance tuning, proxy networks, databases, backups, and security.

## Server Software

### Paper (Recommended for plugins)
- Fork of Spigot with performance patches
- Best plugin compatibility
- Async chunk loading, entity ticking optimizations
- Download: https://papermc.io/downloads

### Velocity (Recommended proxy)
- Modern proxy, better performance than BungeeCord
- Modern forwarding (more secure than legacy)
- Plugin API is different from BungeeCord

### Fabric/NeoForge (For mods)
- Fabric: lightweight, fast updates
- NeoForge: Forge successor, community-driven

## Proxy Network Setup

### Velocity Configuration (velocity.toml)
```toml
[servers]
hub = "172.18.0.2:25565"
survival = "172.18.0.3:25565"
creative = "172.18.0.4:25565"
try = ["hub"]

[forced-hosts]
"hub.example.com" = ["hub"]
"survival.example.com" = ["survival"]

[advanced]
modern-forwarding-secret = "GENERATE-RANDOM-SECRET"
```

### Backend server.properties
```properties
online-mode=false           # Proxy handles auth
server-port=25565
```

### Paper global config (for behind proxy)
```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "SAME-SECRET-AS-VELOCITY"
```

## Performance Tuning

### paper-global.yml Key Settings
```yaml
chunk-system:
  gen-parallelism: default    # Auto-detect CPU cores
  io-threads: default

packet-limiter:
  all-packets:
    max-packet-rate: 5000.0   # Increase if players get kicked
```

### paper-world-defaults.yml
```yaml
entities:
  spawning:
    monster-spawn-range: 6          # Reduce from 8
    per-player-mob-spawns: true     # Better distribution
    
chunks:
  auto-save-interval: 6000         # 5 minutes
  
environment:
  optimize-explosions: true
  treasure-maps:
    find-already-discovered: false  # Huge performance saver
```

### JVM Flags (Aikar's Flags)
```bash
java -Xms10G -Xmx10G \
  -XX:+UseG1GC \
  -XX:+ParallelRefProcEnabled \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+DisableExplicitGC \
  -XX:+AlwaysPreTouch \
  -XX:G1NewSizePercent=30 \
  -XX:G1MaxNewSizePercent=40 \
  -XX:G1HeapRegionSize=8M \
  -XX:G1ReservePercent=20 \
  -XX:G1HeapWastePercent=5 \
  -XX:G1MixedGCCountTarget=4 \
  -XX:InitiatingHeapOccupancyPercent=15 \
  -XX:G1MixedGCLiveThresholdPercent=90 \
  -XX:G1RSetUpdatingPauseTimePercent=5 \
  -XX:SurvivorRatio=32 \
  -XX:+PerfDisableSharedMem \
  -XX:MaxTenuringThreshold=1 \
  -jar paper.jar --nogui
```

## Database Setup

### MariaDB for Plugins
```sql
CREATE DATABASE minecraft;
CREATE USER 'mcuser'@'localhost' IDENTIFIED BY 'secure_password';
GRANT ALL PRIVILEGES ON minecraft.* TO 'mcuser'@'localhost';
FLUSH PRIVILEGES;
```

### HikariCP Settings (most plugins)
```yaml
# Common config pattern
database:
  host: localhost
  port: 3306
  database: minecraft
  username: mcuser
  password: secure_password
  pool-size: 10
```

## Backup Strategies

### DriveBackupV2
```yaml
# Backup to MinIO/S3
type: s3
bucket: minecraft-backups
endpoint: https://minio.example.com
access-key: YOUR_KEY
secret-key: YOUR_SECRET
```

### Manual Backup Script
```bash
#!/bin/bash
DATE=$(date +%Y-%m-%d_%H-%M)
SERVER_DIR="/path/to/server"
BACKUP_DIR="/path/to/backups"

# Stop saving, save all
screen -S minecraft -X stuff "save-off\n"
screen -S minecraft -X stuff "save-all\n"
sleep 10

# Tar the world
tar -czf "$BACKUP_DIR/world-$DATE.tar.gz" "$SERVER_DIR/world"

# Resume saving
screen -S minecraft -X stuff "save-on\n"
```

## Security

### SSH Hardening
```bash
# /etc/ssh/sshd_config
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
Port 2222                    # Non-standard port
```

### Firewall (UFW)
```bash
ufw default deny incoming
ufw allow 25565/tcp          # MC Java
ufw allow 25565/udp          # MC Bedrock (Geyser)
ufw allow 2222/tcp           # SSH
ufw enable
```

### Cloudflare Tunnel (for web panels)
```bash
cloudflared tunnel create minecraft
cloudflared tunnel route dns minecraft panel.example.com
```

## Monitoring

### Spark (Performance)
```
/spark profiler              # CPU profile
/spark tps                   # TPS history
/spark health                # Server health
/spark gc                    # GC stats
```

### Plan (Analytics)
```yaml
# plan/config.yml
webserver:
  port: 8804
  alternative_ip:
    address: plan.example.com
```

## AMP Panel Notes
- Instances stored in `/home/amp/.ampdata/instances/`
- Each instance has its own `Minecraft/` directory
- Plugin files in `Minecraft/plugins/`
- Server logs in `Minecraft/logs/`
- Run commands as `amp` user, not root
- File permissions must be owned by `amp:amp`
