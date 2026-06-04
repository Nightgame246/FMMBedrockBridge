# [EliteMobs] Route Bedrock players to the native Dialog menu (Geyser now renders dialogs)

**Repo:** MagmaGuy/EliteMobs
**Version:** 10.4.0
**Related:** Geyser dialog→Bedrock-form translation (PR #5603, MC 1.21.6)

## Summary

`PlayerStatusScreen(Player)` forces Bedrock players to the chest menu path:

```java
if (!useBookMenus || GeyserDetector.bedrockPlayer(player) || onlyUseBedrockMenus)
    generateChestMenu(player);            // chest
else if (serverOlderThan(1.21.6))
    generateBook(player);                 // book
else
    PlayerStatusScreenDialog.showPlayerStatusDialog(player);  // native dialog
```

On Bedrock this renders as a bare chest container with none of the menu's intended
design. EM already builds the full status menu as native MC dialogs
(`PlayerStatusScreenDialog`), and **Geyser now translates the MC Dialog API into
native Bedrock forms** (since 1.21.6). We verified empirically that cancelling the
Bedrock chest and calling `showPlayerStatusDialog` makes the Bedrock client render a
clean native form, with sub-pages cascading correctly.

## Request

On MC >= 1.21.6, route Bedrock players to the dialog path as well (or add a config
flag, e.g. `useBedrockDialogs: true`), instead of forcing the chest. This gives
Bedrock players the same polished menu Java 1.21.6+ players already get.

## Notes

- Keep the chest path as the fallback for MC < 1.21.6 or if a server opts out.
- We currently work around this in a bridge plugin by intercepting the chest open and
  invoking `showPlayerStatusDialog` ourselves; native routing would let us drop that.

## Environment

- EliteMobs 10.4.0, FreeMinecraftModels 2.7.0
- Geyser-Velocity 2.10.0, Floodgate, Paper 1.21.10 backend, Velocity 3.5.0 proxy
