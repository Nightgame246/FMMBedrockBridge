# [GeyserUtils] `loadSkins` picks the last `.json` in a skin folder → NPE when non-geometry JSONs are present

**Repo:** zimzaza4/GeyserUtils
**Branch/commit:** `main` @ `d045474` (2026-01-11)
**Component:** Geyser extension (`me.zimzaza4.geyserutils.geyser.GeyserUtils`)

## Summary

`loadSkins()` iterates a skin folder and assigns `geometryFile` for **every** `.json` it
sees, keeping the last one returned by `File.listFiles()` (filesystem order, not
alphabetical). If a skin folder contains more than one JSON — e.g. `geometry.json` plus
`model-config.json`, `animations.json`, `animation_controllers.json` — a non-geometry JSON
can be passed to `loadSkin()`, which then NPEs because it has no `minecraft:geometry` key.

## Stack trace

```
java.lang.NullPointerException: Cannot invoke "JsonElement.getAsJsonArray()" because the
  return value of "JsonObject.get(String)" is null
    at me.zimzaza4.geyserutils.geyser.GeyserUtils.loadSkin(GeyserUtils.java:412)
    at me.zimzaza4.geyserutils.geyser.GeyserUtils.loadSkins(GeyserUtils.java:399)
```

The exception is caught (only printed to stderr) so the extension keeps running, but the
log gets spammed with one stack trace per affected skin folder — easily hundreds of lines.

## Root cause

`GeyserUtils.java:384-403`:

```java
for (File folderFile : file.listFiles()) {
    if (folderFile.getName().endsWith(".png"))  textureFile  = folderFile;
    if (folderFile.getName().endsWith(".json")) geometryFile = folderFile; // overwrites each time
}
loadSkin(file.getName(), geometryFile, textureFile);
```

And in `loadSkin()`:

```java
JsonElement json = new JsonParser().parse(new FileReader(geometryFile));
for (JsonElement e : json.getAsJsonObject().get("minecraft:geometry").getAsJsonArray()) {
```

`model-config.json` (etc.) has no `minecraft:geometry` → `.get(...)` returns null →
`.getAsJsonArray()` throws.

## Suggested fix

Match the geometry file explicitly instead of "last `.json` wins":

```java
if (folderFile.getName().endsWith(".geo.json")
        || folderFile.getName().equals("geometry.json")) {
    geometryFile = folderFile;
}
```

Or, in `loadSkin()`, guard the lookup and skip the folder cleanly:

```java
JsonElement geo = json.getAsJsonObject().get("minecraft:geometry");
if (geo == null || !geo.isJsonArray()) {
    // log once, skip this skin instead of NPE-spamming
    return;
}
```

## Operational workaround (no patch required)

Keep exactly one JSON (`geometry.json`) per skin folder:

```bash
find <Geyser>/extensions/geyserutils/skins \
    -mindepth 2 -maxdepth 2 -name '*.json' ! -name 'geometry.json' -delete
```

## Environment

- GeyserUtils 1.0-SNAPSHOT (Geyser extension), `main` @ `d045474`
- Geyser-Velocity 2.10.0-b1141, Velocity 3.5.0 proxy
