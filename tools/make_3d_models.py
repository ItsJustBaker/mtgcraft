"""Builds 3D item models (and their textures) for booster packs and booster boxes.

Packs are thin slabs with crimped foil ends and the wrapper art on both faces; boxes are lidded boxes with an art
panel. Run after make_pack_art.py, from the mtgcraft folder:  python tools/make_3d_models.py
"""
import json
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
import make_pack_art as art  # noqa: E402  (reuses ICONS / WRAP / icon())

TEX = 'src/main/resources/assets/mtgcraft/textures/item'
MOD = 'src/main/resources/assets/mtgcraft/models/item'
THEMES = ['OVERWORLD', 'CREEPER', 'UNDEAD', 'NIGHT', 'NETHER', 'END', 'OCEAN', 'VILLAGE', 'RAID', 'WITCH', 'SCULK',
          'ENDER_DRAGON', 'WITHER']  # Packs.Theme order


def box_textures(name):
    main, dark, light = art.WRAP[name]
    # front: frame + big icon + band
    f = Image.new('RGBA', (32, 32), main + (255,))
    d = ImageDraw.Draw(f)
    d.rectangle([0, 0, 31, 31], outline=dark + (255,))
    d.rectangle([1, 1, 30, 30], outline=light + (255,))
    d.rectangle([5, 3, 26, 22], fill=dark + (255,), outline=light + (255,))
    ic = art.icon(name, 2)
    f.alpha_composite(ic, (8, 5))
    d.rectangle([3, 24, 28, 28], fill=dark + (255,))
    for x in range(5, 27, 3):
        d.point((x, 26), fill=light + (255,))
    f.save(f'{TEX}/box3d_front_{name.lower()}.png')
    # side: stripes + small icon
    s = Image.new('RGBA', (32, 32), main + (255,))
    d = ImageDraw.Draw(s)
    d.rectangle([0, 0, 31, 31], outline=dark + (255,))
    d.rectangle([0, 12, 31, 19], fill=dark + (255,))
    s.alpha_composite(art.icon(name, 1), (12, 12))
    s.save(f'{TEX}/box3d_side_{name.lower()}.png')
    # lid top
    t = Image.new('RGBA', (32, 32), light + (255,))
    d = ImageDraw.Draw(t)
    d.rectangle([0, 0, 31, 31], outline=dark + (255,))
    d.rectangle([3, 3, 28, 28], fill=main + (255,), outline=dark + (255,))
    t.alpha_composite(art.icon(name, 2), (8, 8))
    t.save(f'{TEX}/box3d_top_{name.lower()}.png')


def pack_texture(name):
    # The big wrapper art doubles as the 3D pack's faces (it lives in the item atlas).
    w = art.wrapper(name, 64, 112, True)
    w.save(f'{TEX}/pack3d_{name.lower()}.png')


DISPLAY_PACK = {
    "gui": {"rotation": [10, -25, 0], "translation": [0, 0, 0], "scale": [1.05, 1.05, 1.05]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
    "thirdperson_lefthand": {"rotation": [0, 90, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -70, 10], "translation": [1.5, 3, 1], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 70, -10], "translation": [1.5, 3, 1], "scale": [0.68, 0.68, 0.68]},
}
DISPLAY_BOX = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 1, 0], "scale": [0.85, 0.85, 0.85]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.4, 0.4, 0.4]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
}


def face(tex, uv=None):
    f = {"texture": tex}
    if uv:
        f["uv"] = uv
    return f


def pack_model(name):
    t = f"mtgcraft:item/pack3d_{name.lower()}"
    edge = [0, 0, 1, 16]
    crimp_uv = [0, 0, 16, 1]
    return {
        "textures": {"art": t, "particle": t},
        "gui_light": "front",
        "elements": [
            {"from": [4, 1, 7.5], "to": [12, 15, 8.5],
             "faces": {"north": face("#art", [16, 0, 0, 16]), "south": face("#art", [0, 0, 16, 16]),
                       "east": face("#art", edge), "west": face("#art", edge),
                       "up": face("#art", crimp_uv), "down": face("#art", crimp_uv)}},
            # crimped foil ends, slightly wider and thinner
            {"from": [3.8, 15, 7.7], "to": [12.2, 16, 8.3],
             "faces": {k: face("#art", [0, 0, 16, 1]) for k in ("north", "south", "east", "west", "up", "down")}},
            {"from": [3.8, 0, 7.7], "to": [12.2, 1, 8.3],
             "faces": {k: face("#art", [0, 15, 16, 16]) for k in ("north", "south", "east", "west", "up", "down")}},
        ],
        "display": DISPLAY_PACK,
    }


def box_model(name):
    n = name.lower()
    tex = {"front": f"mtgcraft:item/box3d_front_{n}", "side": f"mtgcraft:item/box3d_side_{n}",
           "top": f"mtgcraft:item/box3d_top_{n}", "particle": f"mtgcraft:item/box3d_front_{n}"}
    return {
        "textures": tex,
        "elements": [
            {"from": [2, 0, 3], "to": [14, 8, 13],
             "faces": {"north": face("#front"), "south": face("#front"), "east": face("#side"),
                       "west": face("#side"), "down": face("#side")}},
            # the lid
            {"from": [1.7, 8, 2.7], "to": [14.3, 10, 13.3],
             "faces": {"north": face("#side", [0, 0, 16, 3]), "south": face("#side", [0, 0, 16, 3]),
                       "east": face("#side", [0, 0, 16, 3]), "west": face("#side", [0, 0, 16, 3]),
                       "up": face("#top"), "down": face("#side")}},
        ],
        "display": DISPLAY_BOX,
    }


os.makedirs(TEX, exist_ok=True)
for name in THEMES + ['SET']:
    pack_texture(name)
    box_textures(name)
    json.dump(pack_model(name), open(f'{MOD}/pack_{name.lower()}.json', 'w'), indent=1)
    json.dump(box_model(name), open(f'{MOD}/box_{name.lower()}.json', 'w'), indent=1)

pack_over = [{"predicate": {"mtgcraft:theme": (i + 1) / 100}, "model": f"mtgcraft:item/pack_{t.lower()}"}
             for i, t in enumerate(THEMES)]
base_pack = pack_model('SET')
base_pack["overrides"] = pack_over
json.dump(base_pack, open(f'{MOD}/booster_pack.json', 'w'), indent=1)
box_over = [{"predicate": {"mtgcraft:theme": (i + 1) / 100}, "model": f"mtgcraft:item/box_{t.lower()}"}
            for i, t in enumerate(THEMES)]
base_box = box_model('SET')
base_box["overrides"] = box_over
json.dump(base_box, open(f'{MOD}/booster_box.json', 'w'), indent=1)
print('ok')
