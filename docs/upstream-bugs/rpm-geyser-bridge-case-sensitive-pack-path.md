# [RPM] Geyser bridge looks for the merged pack under `ResourcePackManager/`, the plugin writes it to `resourcepackmanager/` — breaks on case-sensitive filesystems

**Repo:** MagmaGuy/ResourcePackManager
**Version:** 2.3.0 (still present; first observed in 2.2.2)
**Impact:** on Linux, Bedrock clients get custom models with **no animations and no entity
properties** until an admin manually creates a symlink. Silent — the only hint is
`bridge ready with 0`.

## Summary

The two halves of RPM disagree about the capitalisation of the plugin data directory:

- The **Velocity plugin** writes the merged pack to `plugins/resourcepackmanager/…`
  (lowercase — Velocity derives the directory from the plugin id).
- The **Geyser bridge extension** reads it from `plugins/ResourcePackManager/…`
  (capitalised — the Bukkit-style plugin name).

On Windows/macOS (case-insensitive) this works by accident. On Linux the extension finds
nothing, preloads zero definitions, and logs `bridge ready with 0` — after which Bedrock
clients receive the pack but no custom entity definitions, so models appear without
animations or properties.

## Evidence from a live proxy (RPM 2.3.0, 2026-08-08)

Both lines are from the same `latest.log`, minutes apart — note the capitalisation:

```
[resourcepackmanager]: Merged pack ready at
    …/Proxy01/Minecraft/plugins/resourcepackmanager/work/merged/Bedrock.zip
        ^^^^^^^^^^^^^^^^^^^ lowercase — written by the plugin

[geyser]: [resourcepackmanagergeyserbridge] Preloaded 316 custom Bedrock entity identifiers …
    from …/Proxy01/Minecraft/plugins/ResourcePackManager/work/merged/Bedrock.zip
             ^^^^^^^^^^^^^^^^^^^ capitalised — read by the extension
```

This proxy only works because of the workaround below; without the symlink the second path
does not resolve.

## Steps to reproduce

1. Linux host (case-sensitive filesystem), Velocity proxy.
2. Install RPM 2.3.0 on the proxy; let it create `plugins/resourcepackmanager/`.
3. Ensure `plugins/ResourcePackManager` does **not** exist.
4. Start the proxy and watch the Geyser bridge extension log.

## Expected

The extension resolves the pack the plugin just wrote, and reports
`bridge ready with <n>` where `n > 0`.

## Actual

`bridge ready with 0` — no custom entity definitions registered. Bedrock clients get the pack
but models render without animations/properties. No error, no warning naming the path.

## Workaround in use

```bash
cd <proxy>/plugins
ln -s resourcepackmanager ResourcePackManager
```

Then restart the proxy. This has been required continuously since 2.2.2 and is still required
on 2.3.0.

## Suggested fix

Have the extension resolve the directory case-insensitively, or — better — derive it from the
same constant the plugin uses when writing, rather than from the display name. Failing that,
logging the attempted absolute path when the lookup misses would turn a silent misconfiguration
into a one-line diagnosis.

## Environment

- Velocity 4.1.0-SNAPSHOT (git-2676520c-b14), Debian Bookworm, ext4 (case-sensitive)
- ResourcePackManager 2.3.0 (backend and proxy), Geyser bridge extension built 2026-07-14
- Geyser-Velocity 2.11.1-b1210, Floodgate 2.2.5-SNAPSHOT (b138)
- FreeMinecraftModels 2.10.2 backend on Paper 1.21.x, Java 21
