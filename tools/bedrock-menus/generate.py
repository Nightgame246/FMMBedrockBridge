#!/usr/bin/env python3
"""Phase 7.6 — baut das Bedrock-Pack FMMBridge-EliteMobsMenus aus dem Java-Pack von RPM.

Aufruf:  python3 tools/bedrock-menus/generate.py <ResourcePackManager_RSP.zip>
Ausgabe: target/bedrock-menus/FMMBridge-EliteMobsMenus.mcpack (+ last-run.json)
Erhoeht die Version in tools/bedrock-menus/pack-meta.json — danach committen.

Die Texturen gehoeren MagmaGuy und duerfen nie ins (oeffentliche) Repo. Nur Standardbibliothek.
Zuordnung und Versatz muessen zu BedrockMenuTitle.java passen (gemeinsame Faelle in cases.json).
"""
import json, os, struct, sys, zipfile, zlib
from dataclasses import dataclass

EM_BLOCK_START, EM_BLOCK_END = 0xF0E00, 0xF0F0B
MARKER_BASE, GENERIC_MARKER = 0xE900, 0xE8FF
KNOWN_SPACING = {0xF0EF1, 0xF0EF5}          # muss BedrockMenuTitle.SPACING_CODEPOINTS entsprechen
# Ausgerichtet am Slot-Raster, nicht am Titel (Abnahme 01.10.2026): EliteMobs malt seine Kaestchen
# dorthin, wo Javas Slots liegen (x 8, y 18). Javas Bild liegt relativ dazu bei x −19 und
# y = (13 − ascent) − 18. Bedrocks Raster liegt in der oberen Kistenhaelfte bei x 7 und
# y 10 (grosse Kiste) bzw. 9 (kleine Kiste) — Vanilla chest_screen.json.
# Hergeleitet waeren −19 (8 Titel-x − 19 fuer U+F0EF1 − 8 Slot-x). GEMESSEN (Abnahme 01.10.2026,
# Reparatur-Menue v0.0.3): die gemalten Kaestchen lagen 4 Einheiten links der Slot-Symbole, auf Java
# exakt darunter. Vermutlich verrechnet Java die Abstands-Glyphen anders als hier angenommen.
JAVA_IMAGE_X_FROM_SLOTS = -15
BEDROCK_GRID = {"large": (7, 10), "small": (7, 9)}
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


@dataclass
class Menu:
    codepoint: int
    file: str            # z. B. elitemobs:gui/repairmenu.png
    ascent: int
    texture_name: str    # z. B. elitemobs_repairmenu
    png: bytes
    width: int           # aus dem PNG-Kopf — zwei nightbreak-Bilder sind 213/214 px breit
    height: int


def marker_for(cp):
    if cp < EM_BLOCK_START or cp > EM_BLOCK_END or cp in KNOWN_SPACING:
        raise ValueError(f"kein Menue-Hintergrund: U+{cp:X}")
    return MARKER_BASE + (cp - EM_BLOCK_START)


def offset(ascent, chest, override):
    if override and chest in override:
        return override[chest]["x"], override[chest]["y"]
    gx, gy = BEDROCK_GRID[chest]
    return gx + JAVA_IMAGE_X_FROM_SLOTS, gy + (13 - ascent) - 18


def read_menus(z):
    if "assets/minecraft/font/default.json" not in z.namelist():
        sys.exit("FEHLER: assets/minecraft/font/default.json fehlt — falsches ZIP? Erwartet: ResourcePackManager_RSP.zip")
    providers = json.loads(z.read("assets/minecraft/font/default.json"))["providers"]
    menus, spacing, seen = [], set(), set()
    # RPM's merged pack repeats EliteMobs' font once per content package; Minecraft lets the
    # first provider for a character win, so later duplicates are skipped.
    for p in providers:
        if p.get("type") != "bitmap":
            continue
        for row in p.get("chars", []):
            for ch in row:
                cp = ord(ch)
                if not (EM_BLOCK_START <= cp <= EM_BLOCK_END) or cp in seen:
                    continue
                seen.add(cp)
                if p.get("height", 0) < 0:
                    spacing.add(cp)
                elif p.get("height", 0) >= 40:
                    ns, path = p["file"].split(":", 1)
                    tex = f"assets/{ns}/textures/{path}"
                    name = ns + "_" + os.path.splitext(os.path.basename(path))[0]
                    data = z.read(tex)
                    w, h = struct.unpack(">II", data[16:24])
                    menus.append(Menu(cp, p["file"], p.get("ascent", 0), name, data, w, h))
    if not menus:
        sys.exit("FEHLER: keine Menue-Hintergruende im Pack gefunden — Abbruch, es wird kein leeres Pack gebaut")
    menus.sort(key=lambda m: m.codepoint)
    return menus, spacing


def transparent_png(size=256):
    raw = b"".join(b"\x00" + b"\x00\x00\x00\x00" * size for _ in range(size))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def _image(m, chest, overrides):
    x, y = offset(m.ascent, chest, overrides.get(f"{m.codepoint:X}"))
    marker = chr(marker_for(m.codepoint))
    return {f"fmmbridge_em_{m.texture_name}": {
        "type": "image", "texture": f"textures/ui/fmmbridge_em/{m.texture_name}",
        "size": [m.width, m.height], "anchor_from": "top_left", "anchor_to": "top_left",
        "offset": [x, y], "layer": 1,
        "$atext": "$container_title", "visible": f"(not ($atext - '{marker}' = $atext))"}}


def _cell_binding(name_expr):
    return [{"binding_name": name_expr, "binding_name_override": "#visible",
             "binding_type": "collection", "binding_collection_name": "$item_collection_name"}]


def chest_screen(menus, overrides):
    ui = {"namespace": "chest"}
    for chest in ("large", "small"):
        ui[f"{chest}_chest_panel_top_half"] = {"modifications": [{
            "array_name": "controls", "operation": "insert_front",
            "value": [_image(m, chest, overrides) for m in menus]}]}
    ui["chest_grid_item@common.container_item"] = {
        "$item_collection_name": "container_items",
        "$background_images": "chest.fmmbridge_cell_images"}
    ui["fmmbridge_cell_images"] = {"type": "panel", "controls": [
        {"fmmbridge_plain_bg": {"type": "panel", "$atext": "$container_title",
                                "visible": f"($atext - '{chr(GENERIC_MARKER)}' = $atext)",
                                "controls": [{"cell_image@common.cell_image": {
                                    "$cell_selected_binding_name|default": "#is_selected_slot",
                                    "visible": True,
                                    "bindings": _cell_binding("(not $cell_selected_binding_name)")}}]}},
        {"cell_image_selected@common.cell_image_selected": {
            "$cell_selected_binding_name|default": "#is_selected_slot",
            "visible": False,
            "bindings": _cell_binding("$cell_selected_binding_name")}}]}
    return ui


def build(java_zip, out_dir, meta, overrides):
    menus, spacing = read_menus(zipfile.ZipFile(java_zip))
    unknown = spacing - KNOWN_SPACING
    if unknown:
        print("WARNUNG: neue Abstands-Zeichen, BedrockMenuTitle.SPACING_CODEPOINTS nachziehen: "
              + ", ".join(f"U+{c:X}" for c in sorted(unknown)))
    meta["version"] += 1
    version = [0, 0, meta["version"]]
    manifest = {"format_version": 2,
                "header": {"name": "FMMBridge EliteMobs Menus",
                           "description": "EliteMobs-Menue-Hintergruende fuer Bedrock (FMMBedrockBridge Phase 7.6)",
                           "uuid": meta["header_uuid"], "version": version, "min_engine_version": [1, 21, 0]},
                "modules": [{"type": "resources", "uuid": meta["module_uuid"], "version": version}]}
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "FMMBridge-EliteMobsMenus.mcpack")
    glyph = transparent_png()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("ui/chest_screen.json", json.dumps(chest_screen(menus, overrides), ensure_ascii=False, indent=1))
        for page in ("E8", "E9", "EA"):
            z.writestr(f"font/glyph_{page}.png", glyph)
        for m in menus:
            z.writestr(f"textures/ui/fmmbridge_em/{m.texture_name}.png", m.png)
    _report(out_dir, menus)
    return out


def _report(out_dir, menus):
    path = os.path.join(out_dir, "last-run.json")
    now = {f"{m.codepoint:X}": m.file for m in menus}
    before = json.load(open(path)) if os.path.exists(path) else {}
    added = sorted(set(now) - set(before))
    removed = sorted(set(before) - set(now))
    print(f"{len(menus)} Menues. Neu: {added or '-'}  Entfallen: {removed or '-'}")
    json.dump(now, open(path, "w"), indent=1)


def main(argv):
    if len(argv) != 2:
        sys.exit(__doc__)
    meta_path = os.path.join(HERE, "pack-meta.json")
    meta = json.load(open(meta_path))
    overrides = {k: v for k, v in json.load(open(os.path.join(HERE, "overrides.json"))).items() if not k.startswith("_")}
    out = build(argv[1], os.path.join(ROOT, "target", "bedrock-menus"), meta, overrides)
    json.dump(meta, open(meta_path, "w"), indent=2)
    print(f"Pack: {out}  Version: 0.0.{meta['version']}  (pack-meta.json committen)")


if __name__ == "__main__":
    main(sys.argv)
