package de.crazypandas.fmmbridgeextension;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JavaItemGeometryConverter#convertToGeo}.
 *
 * Reference: Geyser java2bedrock output (Kafal pack) for EM gear —
 *   - Java item space is centred at 8,8,8: cube origin = [from.x-8, from.y, from.z-8]
 *   - Java per-face UVs live in 0..16 model space and are scaled to Bedrock pixels
 *   - non-rotated elements become cubes inside geyser_custom_z
 *   - each rotated element becomes a child bone of geyser_custom_z;
 *     bone pivot = [origin.x-8, origin.y, origin.z-8], X/Y rotation negated, Z kept
 */
class JavaItemGeometryConverterTest {

    private static final Gson GSON = new Gson();
    private final JavaItemGeometryConverter converter = new JavaItemGeometryConverter();

    private JsonObject model(String json) {
        return GSON.fromJson(json, JsonObject.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> description(Map<String, Object> geo) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) geo.get("minecraft:geometry");
        return (Map<String, Object>) list.get(0).get("description");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> bones(Map<String, Object> geo) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) geo.get("minecraft:geometry");
        return (List<Map<String, Object>>) list.get(0).get("bones");
    }

    private Map<String, Object> bone(Map<String, Object> geo, String name) {
        return bones(geo).stream()
                .filter(b -> name.equals(b.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("bone not found: " + name));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cubes(Map<String, Object> bone) {
        return (List<Map<String, Object>>) bone.get("cubes");
    }

    @Test
    void buildsGeyserCustomBindingHierarchyWithCubesNotTextureMeshes() {
        Map<String, Object> geo = converter.convertToGeo(model("""
                {"texture_size":[64,64],"elements":[
                  {"from":[7,2,7],"to":[9,10,9],"faces":{}}
                ]}"""), "em_test");

        assertEquals("geyser_custom", bone(geo, "geyser_custom_x").get("parent"));
        assertEquals("geyser_custom_x", bone(geo, "geyser_custom_y").get("parent"));
        assertEquals("geyser_custom_y", bone(geo, "geyser_custom_z").get("parent"));
        assertNotNull(bone(geo, "geyser_custom").get("binding"));
        // real geometry, not the flat-sprite stub
        assertNotNull(bone(geo, "geyser_custom_z").get("cubes"));
        assertNull(bone(geo, "geyser_custom_z").get("texture_meshes"));
    }

    @Test
    void convertsElementToCubeWithCenteredXZ() {
        // x/z shift by -8 (Java item space centred at 8,8,8); y is NOT shifted.
        Map<String, Object> geo = converter.convertToGeo(model("""
                {"texture_size":[64,64],"elements":[
                  {"from":[7,2,7],"to":[9,10,9],"faces":{}}
                ]}"""), "em_test");

        List<Map<String, Object>> cubes = cubes(bone(geo, "geyser_custom_z"));
        assertEquals(1, cubes.size());
        assertArrayEquals(new double[]{-1, 2, -1}, (double[]) cubes.get(0).get("origin"), 1e-6);
        assertArrayEquals(new double[]{2, 8, 2}, (double[]) cubes.get(0).get("size"), 1e-6);
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsPerFaceUv() {
        Map<String, Object> geo = converter.convertToGeo(model("""
                {"texture_size":[64,64],"elements":[
                  {"from":[7,2,7],"to":[9,10,9],
                   "faces":{"north":{"uv":[4.75,7.75,5.25,9.75]}}}
                ]}"""), "em_test");

        Map<String, Object> uv = (Map<String, Object>) cubes(bone(geo, "geyser_custom_z")).get(0).get("uv");
        Map<String, Object> north = (Map<String, Object>) uv.get("north");
        assertArrayEquals(new double[]{19.0, 31.0}, (double[]) north.get("uv"), 1e-6);
        assertArrayEquals(new double[]{2.0, 8.0}, (double[]) north.get("uv_size"), 1e-6);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsMirroredUvSizeWhenScaling() {
        Map<String, Object> geo = converter.convertToGeo(model("""
                {"texture_size":[64,64],"elements":[
                  {"from":[7,2,7],"to":[9,10,9],
                   "faces":{"up":{"uv":[10.25,0.5,9.75,0]}}}
                ]}"""), "em_test");

        Map<String, Object> uv = (Map<String, Object>) cubes(bone(geo, "geyser_custom_z")).get(0).get("uv");
        Map<String, Object> up = (Map<String, Object>) uv.get("up");
        assertArrayEquals(new double[]{41.0, 2.0}, (double[]) up.get("uv"), 1e-6);
        assertArrayEquals(new double[]{-2.0, -2.0}, (double[]) up.get("uv_size"), 1e-6);
    }

    @Test
    void rotatedElementBecomesChildBoneOfGeyserCustomZ() {
        // Java rotation {angle:-22.5, axis:x, origin:[8,-0.23097,7.5]}.
        Map<String, Object> geo = converter.convertToGeo(model("""
                {"texture_size":[64,64],"elements":[
                  {"from":[6.5,-10.48578,2.41484],"to":[9.5,-8.48578,6.41484],
                   "rotation":{"angle":-22.5,"axis":"x","origin":[8,-0.23097,7.5]},
                   "faces":{}}
                ]}"""), "em_test");

        // rotated element does not sit directly in geyser_custom_z
        List<Map<String, Object>> zCubes = cubes(bone(geo, "geyser_custom_z"));
        assertTrue(zCubes == null || zCubes.isEmpty());

        Map<String, Object> rotBone = bones(geo).stream()
                .filter(b -> "geyser_custom_z".equals(b.get("parent")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rotation bone parented to geyser_custom_z"));

        // pivot: x/z shifted -8, y kept
        assertArrayEquals(new double[]{0, -0.23097, -0.5}, (double[]) rotBone.get("pivot"), 1e-6);
        // X rotation negated (java2bedrock convention)
        assertArrayEquals(new double[]{22.5, 0, 0}, (double[]) rotBone.get("rotation"), 1e-6);

        List<Map<String, Object>> rotCubes = cubes(rotBone);
        assertEquals(1, rotCubes.size());
        assertArrayEquals(new double[]{-1.5, -10.48578, -5.58516},
                (double[]) rotCubes.get(0).get("origin"), 1e-5);
    }

    @Test
    void readsTextureSizeAndIdentifierIntoDescription() {
        Map<String, Object> geo = converter.convertToGeo(model("""
                {"texture_size":[64,64],"elements":[
                  {"from":[7,2,7],"to":[9,10,9],"faces":{}}
                ]}"""), "em_test");

        Map<String, Object> desc = description(geo);
        assertEquals(64, ((Number) desc.get("texture_width")).intValue());
        assertEquals(64, ((Number) desc.get("texture_height")).intValue());
        assertEquals("geometry.fmmbridge.em_test", desc.get("identifier"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsGenericDisplayTransformsLikeJava2BedrockCubeItems() {
        Map<String, Object> root = converter.generateAnimations(model("""
                {"display":{
                  "thirdperson_righthand":{"translation":[0,1.25,1.75]},
                  "thirdperson_lefthand":{"translation":[0,1.25,1.5]},
                  "firstperson_righthand":{"rotation":[0,13,0],"translation":[3.5,-0.5,-0.5],"scale":[0.9,0.9,0.9]},
                  "head":{"translation":[0,-3.75,0],"scale":[1.55,1.55,1.55]}
                }}"""), "em_test");

        Map<String, Object> animations = (Map<String, Object>) root.get("animations");
        Map<String, Object> thirdPerson = (Map<String, Object>) animations.get(
                "animation.fmmbridge.gear.em_test.thirdperson_main_hand");
        Map<String, Object> firstPerson = (Map<String, Object>) animations.get(
                "animation.fmmbridge.gear.em_test.firstperson_main_hand");
        Map<String, Object> offHand = (Map<String, Object>) animations.get(
                "animation.fmmbridge.gear.em_test.firstperson_off_hand");
        Map<String, Object> head = (Map<String, Object>) animations.get(
                "animation.fmmbridge.gear.em_test.head");

        Map<String, Object> thirdPersonBone = animationBone(thirdPerson, "geyser_custom_x");
        assertArrayEquals(new double[]{0, 1.25, 1.75}, (double[]) thirdPersonBone.get("position"), 1e-6);
        assertNull(thirdPersonBone.get("scale"));
        assertNull(animationBone(thirdPerson, "geyser_custom_y").get("rotation"));
        assertArrayEquals(new double[]{90, 0, 0},
                (double[]) animationBone(thirdPerson, "geyser_custom").get("rotation"), 1e-6);

        Map<String, Object> firstPersonBone = animationBone(firstPerson, "geyser_custom_x");
        assertArrayEquals(new double[]{0, 0, 0}, (double[]) firstPersonBone.get("rotation"), 1e-6);
        assertArrayEquals(new double[]{-3.5, -0.5, -0.5}, (double[]) firstPersonBone.get("position"), 1e-6);
        assertArrayEquals(new double[]{0.9, 0.9, 0.9}, (double[]) firstPersonBone.get("scale"), 1e-6);
        assertArrayEquals(new double[]{0, -13, 0},
                (double[]) animationBone(firstPerson, "geyser_custom_y").get("rotation"), 1e-6);

        Map<String, Object> offHandBone = animationBone(offHand, "geyser_custom_x");
        assertArrayEquals(new double[]{0, 0, 0}, (double[]) offHandBone.get("rotation"), 1e-6);
        assertArrayEquals(new double[]{3.5, -0.5, -0.5}, (double[]) offHandBone.get("position"), 1e-6);

        Map<String, Object> headBone = animationBone(head, "geyser_custom_x");
        assertArrayEquals(new double[]{0, -3.75, 0}, (double[]) headBone.get("position"), 1e-6);
        assertArrayEquals(new double[]{0.96875, 0.96875, 0.96875}, (double[]) headBone.get("scale"), 1e-6);
        assertArrayEquals(new double[]{0, 19.9, 0},
                (double[]) animationBone(head, "geyser_custom").get("position"), 1e-6);
    }

    @Test
    @SuppressWarnings("unchecked")
    void axesUseHandheldFirstPersonBasis() {
        Map<String, Object> root = converter.generateAnimations(model("""
                {"display":{
                  "firstperson_righthand":{"rotation":[0,13,0],"translation":[3.5,-0.5,-0.5],"scale":[0.9,0.9,0.9]}
                },
                "groups":[{"name":"Axe"}]}"""), "em_test_axe");

        Map<String, Object> animations = (Map<String, Object>) root.get("animations");
        Map<String, Object> firstPerson = (Map<String, Object>) animations.get(
                "animation.fmmbridge.gear.em_test_axe.firstperson_main_hand");
        Map<String, Object> firstPersonBone = animationBone(firstPerson, "geyser_custom_x");

        assertArrayEquals(new double[]{0, 13, 0}, (double[]) firstPersonBone.get("rotation"), 1e-6);
        assertArrayEquals(new double[]{3.5, 1.1, -1.3}, (double[]) firstPersonBone.get("position"), 1e-6);
        assertArrayEquals(new double[]{0.612, 0.612, 0.612}, (double[]) firstPersonBone.get("scale"), 1e-6);
        assertArrayEquals(new double[]{0, -90, 0},
                (double[]) animationBone(firstPerson, "geyser_custom_y").get("rotation"), 1e-6);
        assertArrayEquals(new double[]{0, 0, 25},
                (double[]) animationBone(firstPerson, "geyser_custom_z").get("rotation"), 1e-6);
        assertArrayEquals(new double[]{53.79601, 51.7101, -83.00307},
                (double[]) animationBone(firstPerson, "geyser_custom").get("rotation"), 1e-6);
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachableUsesDisableAnimationForHeadWhenModelHasNoHeadTransform() {
        Map<String, Object> attachable = converter.generateAttachable(model("""
                {"display":{"thirdperson_righthand":{"translation":[0,1.25,1.75]}}}
                """), "em_test");
        assertEquals("1.10.0", attachable.get("format_version"));
        Map<String, Object> description = (Map<String, Object>) ((Map<String, Object>) attachable.get("minecraft:attachable"))
                .get("description");
        Map<String, Object> animations = (Map<String, Object>) description.get("animations");
        Map<String, Object> scripts = (Map<String, Object>) description.get("scripts");
        List<Object> animate = (List<Object>) scripts.get("animate");

        assertFalse(animations.containsKey("head"));
        assertTrue(animate.contains(Map.of("disable", "v.head")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void corruptedTridentStyleDisplayUsesCubeItemPoseInsteadOfSpritePose() {
        Map<String, Object> root = converter.generateAnimations(model("""
                {"display":{
                  "thirdperson_righthand":{"translation":[0,1.25,1.75]},
                  "thirdperson_lefthand":{"translation":[0,1.25,1.5]},
                  "firstperson_righthand":{"translation":[2.75,3,0]},
                  "firstperson_lefthand":{"translation":[2.75,3,0]}
                }}"""), "em_corrupted_trident");

        Map<String, Object> animations = (Map<String, Object>) root.get("animations");
        Map<String, Object> thirdPerson = (Map<String, Object>) animations.get(
                "animation.fmmbridge.gear.em_corrupted_trident.thirdperson_main_hand");

        assertArrayEquals(new double[]{0, 1.25, 1.75},
                (double[]) animationBone(thirdPerson, "geyser_custom_x").get("position"), 1e-6);
        assertNull(animationBone(thirdPerson, "geyser_custom_y").get("rotation"));
        assertNull(animationBone(thirdPerson, "geyser_custom_z").get("rotation"));
        assertArrayEquals(new double[]{90, 0, 0},
                (double[]) animationBone(thirdPerson, "geyser_custom").get("rotation"), 1e-6);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> animationBone(Map<String, Object> animation, String boneName) {
        return (Map<String, Object>) ((Map<String, Object>) animation.get("bones")).get(boneName);
    }
}
