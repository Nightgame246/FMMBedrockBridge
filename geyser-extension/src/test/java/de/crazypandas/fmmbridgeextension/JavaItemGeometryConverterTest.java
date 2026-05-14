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
 *   - per-face UV [u1,v1,u2,v2] -> {"uv":[u1,v1],"uv_size":[u2-u1,v2-v1]}
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
        assertArrayEquals(new double[]{4.75, 7.75}, (double[]) north.get("uv"), 1e-6);
        assertArrayEquals(new double[]{0.5, 2.0}, (double[]) north.get("uv_size"), 1e-6);
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
}
