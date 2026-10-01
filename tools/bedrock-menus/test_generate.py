"""Tests fuer generate.py — nur Standardbibliothek. Lauf: python3 -m unittest discover -s tools/bedrock-menus"""
import io, json, os, struct, sys, tempfile, unittest, zipfile, zlib

sys.path.insert(0, os.path.dirname(__file__))
import generate as g

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CASES = json.load(open(os.path.join(ROOT, "src/test/resources/bedrock-menus/cases.json"), encoding="utf-8"))


def png(w, h):
    raw = b"".join(b"\x00" + b"\x00\x00\x00\x00" * w for _ in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def java_pack(providers, textures):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("assets/minecraft/font/default.json", json.dumps({"providers": providers}))
        for path, data in textures.items():
            z.writestr(path, data)
    buf.seek(0)
    return buf


MINI = [
    {"type": "bitmap", "file": "elitemobs:gui/repairmenu.png", "ascent": 136, "height": 256, "chars": [chr(0xF0E01)]},
    {"type": "bitmap", "file": "elitemobs:gui/shop.png", "ascent": 130, "height": 256, "chars": [chr(0xF0E02)]},
    {"type": "bitmap", "file": "nightbreak:gui/setup.png", "ascent": 45, "height": 256, "chars": [chr(0xF0F0B)]},
    {"type": "bitmap", "file": "elitemobs:ui/space_split.png", "ascent": -32768, "height": -19, "chars": [chr(0xF0EF1)]},
]
MINI_TEX = {
    "assets/elitemobs/textures/gui/repairmenu.png": png(256, 256),
    "assets/elitemobs/textures/gui/shop.png": png(256, 256),
    "assets/nightbreak/textures/gui/setup.png": png(214, 256),
}


class MarkerTest(unittest.TestCase):
    def test_shared_marker_cases(self):
        for c in CASES["markers"]:
            self.assertEqual(int(c["marker"], 16), g.marker_for(int(c["em"], 16)), c)

    def test_spacing_has_no_marker(self):
        for s in CASES["spacing"]:
            with self.assertRaises(ValueError):
                g.marker_for(int(s, 16))


class OffsetTest(unittest.TestCase):
    def test_large_chest_matches_spike(self):
        self.assertEqual((-11, -134), g.offset(136, "large", None))

    def test_small_chest_one_lower_top(self):
        self.assertEqual((-11, -135), g.offset(136, "small", None))

    def test_ascent_shifts_down(self):
        self.assertEqual((-11, -128), g.offset(130, "large", None))

    def test_override_wins(self):
        self.assertEqual((-5, -100), g.offset(136, "large", {"large": {"x": -5, "y": -100}}))


class ReadTest(unittest.TestCase):
    def test_reads_backgrounds_and_spacing(self):
        menus, spacing = g.read_menus(zipfile.ZipFile(java_pack(MINI, MINI_TEX)))
        self.assertEqual([0xF0E01, 0xF0E02, 0xF0F0B], [m.codepoint for m in menus])
        self.assertEqual({0xF0EF1}, spacing)
        self.assertEqual("elitemobs_repairmenu", menus[0].texture_name)

    def test_refuses_pack_without_menus(self):
        with self.assertRaises(SystemExit):
            g.read_menus(zipfile.ZipFile(java_pack([MINI[3]], {})))


class BuildTest(unittest.TestCase):
    def test_pack_contents(self):
        with tempfile.TemporaryDirectory() as tmp:
            meta = {"header_uuid": "00000000-0000-0000-0000-000000000001",
                    "module_uuid": "00000000-0000-0000-0000-000000000002", "version": 4}
            out = g.build(java_pack(MINI, MINI_TEX), tmp, meta, {})
            z = zipfile.ZipFile(out)
            names = set(z.namelist())
            for page in ("E8", "E9", "EA"):
                self.assertIn(f"font/glyph_{page}.png", names)
            for t in ("elitemobs_repairmenu", "elitemobs_shop", "nightbreak_setup"):
                self.assertIn(f"textures/ui/fmmbridge_em/{t}.png", names)
            ui = json.loads(z.read("ui/chest_screen.json").decode("utf-8"))
            for half in ("large_chest_panel_top_half", "small_chest_panel_top_half"):
                images = [list(v.keys())[0] for v in ui[half]["modifications"][0]["value"]]
                self.assertEqual(3, len(images), half)
            self.assertEqual("chest.fmmbridge_cell_images",
                             ui["chest_grid_item@common.container_item"]["$background_images"])
            large = {list(v.keys())[0]: list(v.values())[0]
                     for v in ui["large_chest_panel_top_half"]["modifications"][0]["value"]}
            self.assertEqual([214, 256], large["fmmbridge_em_nightbreak_setup"]["size"])
            self.assertEqual([256, 256], large["fmmbridge_em_elitemobs_repairmenu"]["size"])
            manifest = json.loads(z.read("manifest.json"))
            self.assertEqual([0, 0, 5], manifest["header"]["version"])
            self.assertEqual(5, meta["version"])

    def test_visibility_uses_marker_character(self):
        with tempfile.TemporaryDirectory() as tmp:
            meta = {"header_uuid": "a", "module_uuid": "b", "version": 0}
            out = g.build(java_pack(MINI, MINI_TEX), tmp, meta, {})
            ui = zipfile.ZipFile(out).read("ui/chest_screen.json").decode("utf-8")
            self.assertIn("", ui)
            self.assertIn("", ui)
            self.assertIn("", ui)


if __name__ == "__main__":
    unittest.main()
