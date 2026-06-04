# [RPM] Black shadow artifacts on custom models rendered for Bedrock clients

**Repo:** MagmaGuy/ResourcePackManager
**Version:** 2.0.1 (still present; not fixed since first observed in 2.0.0)
**Related:** FreeMinecraftModels 2.7.0, Geyser-Velocity 2.10.0

## Summary

Custom models converted by RPM and delivered to Bedrock clients render with black shadow /
self-shadowing artifacts on parts of the geometry. Java clients viewing the same FMM model
do not show the artifact, so it appears to be introduced during the Java→Bedrock conversion
(geometry, normals, or render-controller / material setup) rather than in the source model.

## Steps to reproduce

1. FMM 2.7.0 model rendered natively for Bedrock via RPM 2.0.1 Network-Mode.
2. Join as a Bedrock client and look at the model from several angles / lighting conditions.
3. Compare against the same model on a Java client.

## Expected

Bedrock rendering matches the Java rendering — no spurious black faces/shadows.

## Actual

Dark/black shading appears on some faces that should be lit normally.

## Notes / things to attach when filing

> TODO (Fabi): attach before/after screenshots (Bedrock vs Java) and name the specific
> model(s) where it's most visible, plus the EM boss it belongs to if applicable. Also note
> whether it's lighting-angle dependent or constant.

Likely suspects worth mentioning to MagmaGuy:
- Bedrock geometry `normalized_uvs` / face normals flipped on mirrored cubes
- Missing or incorrect `minecraft:material_instances` / render-controller emissive setup
- `per_texture_uv_size` rounding (must be integer — known historical packer bug)

## Environment

- ResourcePackManager 2.0.1
- FreeMinecraftModels 2.7.0
- Geyser-Velocity 2.10.0-b1141
- Paper 1.21.x backend, Velocity 3.5.0 proxy
