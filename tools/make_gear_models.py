"""Builds 3D item models (and their textures) for the Deck Box, the Universal Deck Box, the Duel Gauntlet and the Binder.

Deck boxes are small lidded boxes with the top of the deck peeking out under the lid; the gauntlet is an armoured
glove standing on its cuff, with a glowing gem on the back of the hand. Run from the mtgcraft folder:
    python tools/make_gear_models.py
"""
import json
import os

from PIL import Image, ImageDraw

TEX = 'src/main/resources/assets/mtgcraft/textures/item'
MOD = 'src/main/resources/assets/mtgcraft/models/item'

DISPLAY_BOX = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 1, 0], "scale": [0.95, 0.95, 0.95]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0], "scale": [0.55, 0.55, 0.55]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 2, 0], "scale": [0.55, 0.55, 0.55]},
}

DISPLAY_GLOVE = {
    "gui": {"rotation": [20, 210, 0], "translation": [0, 0.5, 0], "scale": [0.95, 0.95, 0.95]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [0, 1, 1], "scale": [0.6, 0.6, 0.6]},
    "thirdperson_lefthand": {"rotation": [0, 90, 0], "translation": [0, 1, 1], "scale": [0.6, 0.6, 0.6]},
    "firstperson_righthand": {"rotation": [0, 100, 10], "translation": [1, 1, 0], "scale": [0.6, 0.6, 0.6]},
    "firstperson_lefthand": {"rotation": [0, 100, 10], "translation": [1, 1, 0], "scale": [0.6, 0.6, 0.6]},
}


def rgba(c, a=255):
    return tuple(c) + (a,)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def save(img, name):
    img.save(f'{TEX}/{name}.png')


def face(tex, uv=None, cull=None):
    f = {"texture": tex}
    if uv:
        f["uv"] = uv
    return f


def box(frm, to, faces):
    return {"from": frm, "to": to, "faces": faces}


def all_faces(tex, skip=()):
    return {d: face(tex) for d in ("north", "south", "east", "west", "up", "down") if d not in skip}


# ------------------------------------------------------------------ deck boxes

def deck_box_textures(prefix, main, dark, light, emblem):
    """Front (with emblem), side, lid and card-edge textures for one deck box colour scheme."""
    f = Image.new('RGBA', (16, 16), rgba(main))
    d = ImageDraw.Draw(f)
    d.rectangle([0, 0, 15, 15], outline=rgba(dark))
    d.line([1, 1, 14, 1], fill=rgba(light))
    emblem(f, d)
    save(f, f'{prefix}_front')

    s = Image.new('RGBA', (16, 16), rgba(main))
    d = ImageDraw.Draw(s)
    d.rectangle([0, 0, 15, 15], outline=rgba(dark))
    d.rectangle([0, 6, 15, 9], fill=rgba(dark))
    d.line([0, 7, 15, 7], fill=rgba(light))
    save(s, f'{prefix}_side')

    lid = Image.new('RGBA', (16, 16), rgba(shade(main, 1.15)))
    d = ImageDraw.Draw(lid)
    d.rectangle([0, 0, 15, 15], outline=rgba(dark))
    d.rectangle([2, 2, 13, 13], outline=rgba(light))
    d.rectangle([6, 6, 9, 9], fill=rgba((224, 182, 90)))
    save(lid, f'{prefix}_lid')


def cards_texture():
    """Card edges seen under the lid: a stack of cream cards with dark lines between them."""
    c = Image.new('RGBA', (16, 16), rgba((236, 228, 206)))
    d = ImageDraw.Draw(c)
    for y in range(0, 16, 2):
        d.line([0, y, 15, y], fill=rgba((180, 168, 140)))
    d.rectangle([0, 0, 15, 15], outline=rgba((70, 60, 50)))
    save(c, 'deckbox3d_cards')


def blue_emblem(img, d):
    d.rectangle([5, 5, 10, 10], fill=rgba((224, 182, 90)), outline=rgba((150, 110, 40)))
    d.point((7, 7), fill=rgba((255, 236, 170)))


MANA = [(246, 239, 208), (63, 127, 214), (58, 50, 56), (217, 73, 58), (63, 166, 90)]


def mana_emblem(img, d):
    # five mana pips in a little ring
    spots = [(7, 4), (10, 6), (9, 10), (5, 10), (4, 6)]
    for (x, y), col in zip(spots, MANA):
        d.rectangle([x, y, x + 1, y + 1], fill=rgba(col))
    d.point((7, 7), fill=rgba((224, 182, 90)))
    d.point((8, 8), fill=rgba((224, 182, 90)))


def deck_box_model(prefix):
    t = f"mtgcraft:item/{prefix}"
    return {
        "textures": {"front": f"{t}_front", "side": f"{t}_side", "lid": f"{t}_lid",
                     "cards": "mtgcraft:item/deckbox3d_cards", "particle": f"{t}_front"},
        "gui_light": "side",
        "elements": [
            # the box
            box([4, 0, 5], [12, 9, 11], {
                "north": face("#front"), "south": face("#front"),
                "east": face("#side"), "west": face("#side"), "down": face("#side"),
                "up": face("#side")}),
            # the deck, peeking out above the box (solid, so there's nothing to see through)
            box([4.5, 9, 5.5], [11.5, 10.5, 10.5], {
                "north": face("#cards"), "south": face("#cards"),
                "east": face("#cards"), "west": face("#cards"),
                "up": face("#cards"), "down": face("#cards")}),
            # the lid, slightly wider, sitting a little open
            box([3.7, 10.5, 4.7], [12.3, 13, 11.3], {
                "north": face("#side", [0, 5, 16, 10]), "south": face("#side", [0, 5, 16, 10]),
                "east": face("#side", [0, 5, 16, 10]), "west": face("#side", [0, 5, 16, 10]),
                "up": face("#lid"), "down": face("#side")}),
            # a clasp on the front
            box([7.25, 7, 4.6], [8.75, 11.5, 5], {
                "north": face("#lid", [6, 6, 10, 10]), "south": face("#lid", [6, 6, 10, 10]),
                "east": face("#lid", [6, 6, 10, 10]),
                "west": face("#lid", [6, 6, 10, 10]), "up": face("#lid", [6, 6, 10, 10]),
                "down": face("#lid", [6, 6, 10, 10])}),
        ],
        "display": DISPLAY_BOX,
    }


# ------------------------------------------------------------------ gauntlet

def gauntlet_textures():
    steel = (70, 76, 92)
    s = Image.new('RGBA', (16, 16), rgba(steel))
    d = ImageDraw.Draw(s)
    for x in range(16):
        for y in range(16):
            # brushed metal: a soft diagonal sheen
            f = 1.0 + 0.18 * (1 - abs(((x + y) % 16) - 8) / 8)
            d.point((x, y), fill=rgba(shade(steel, f)))
    d.rectangle([0, 0, 15, 15], outline=rgba(shade(steel, 0.6)))
    save(s, 'gauntlet3d_steel')

    leather = (110, 70, 40)
    l = Image.new('RGBA', (16, 16), rgba(leather))
    d = ImageDraw.Draw(l)
    for y in (3, 12):
        for x in range(0, 16, 2):
            d.point((x, y), fill=rgba((214, 190, 140)))  # stitching
    d.rectangle([0, 0, 15, 1], fill=rgba((150, 156, 170)))  # metal rim
    d.rectangle([0, 14, 15, 15], fill=rgba(shade(leather, 0.7)))
    save(l, 'gauntlet3d_leather')

    gold = (224, 182, 90)
    g = Image.new('RGBA', (16, 16), rgba(gold))
    d = ImageDraw.Draw(g)
    d.rectangle([0, 0, 15, 15], outline=rgba(shade(gold, 0.6)))
    d.line([1, 1, 14, 1], fill=rgba(shade(gold, 1.2)))
    save(g, 'gauntlet3d_gold')

    gem = Image.new('RGBA', (16, 16), rgba((60, 210, 255)))
    d = ImageDraw.Draw(gem)
    d.rectangle([0, 0, 15, 15], outline=rgba((20, 110, 160)))
    d.rectangle([3, 3, 7, 7], fill=rgba((200, 245, 255)))
    save(gem, 'gauntlet3d_gem')


def gauntlet_model():
    steel, leather, gold, gem = "#steel", "#leather", "#gold", "#gem"
    elements = [
        # cuff
        box([4.5, 0, 4.5], [11.5, 4, 11.5], all_faces(leather)),
        # back of the hand / palm
        box([5, 4, 6], [11, 10, 10], all_faces(steel)),
        # knuckle guard
        box([4.8, 9.5, 5.6], [11.2, 11, 10.4], all_faces(gold)),
    ]
    # four fingers, the middle ones longer
    xs = [(5, 6.4, 13.5), (6.55, 7.95, 15), (8.05, 9.45, 14.5), (9.6, 11, 13)]
    for x0, x1, top in xs:
        elements.append(box([x0, 11, 6.6], [x1, top, 9.4], all_faces(steel, skip=("down",))))
        elements.append(box([x0 - 0.05, top - 1, 6.5], [x1 + 0.05, top - 0.4, 9.5], all_faces(gold, skip=("down",))))
    # thumb
    elements.append(box([11, 5.5, 6.8], [12.6, 9.5, 9.2], all_faces(steel)))
    elements.append(box([11.3, 9.5, 7], [12.4, 11, 9], all_faces(steel, skip=("down",))))
    # the gem on both sides of the hand
    elements.append(box([7, 6, 5.5], [9, 8.5, 6], all_faces(gem, skip=("south",))))
    elements.append(box([7, 6, 10], [9, 8.5, 10.5], all_faces(gem, skip=("north",))))
    return {
        "textures": {"steel": "mtgcraft:item/gauntlet3d_steel", "leather": "mtgcraft:item/gauntlet3d_leather",
                     "gold": "mtgcraft:item/gauntlet3d_gold", "gem": "mtgcraft:item/gauntlet3d_gem",
                     "particle": "mtgcraft:item/gauntlet3d_steel"},
        "gui_light": "side",
        "elements": elements,
        "display": DISPLAY_GLOVE,
    }


# ------------------------------------------------------------------ binder

def binder_textures():
    leather, dark, gold = (92, 44, 120), (46, 20, 62), (220, 180, 80)
    c = Image.new('RGBA', (32, 32), rgba(leather))
    d = ImageDraw.Draw(c)
    for y in range(32):
        for x in range(32):
            if (x * 7 + y * 13) % 11 == 0:
                c.putpixel((x, y), rgba(shade(leather, 0.85)))
    d.rectangle([0, 0, 31, 31], outline=rgba(dark))
    for i in range(2, 30, 2):  # stitching
        d.point((i, 2), fill=rgba(gold)); d.point((i, 29), fill=rgba(gold))
        d.point((2, i), fill=rgba(gold)); d.point((29, i), fill=rgba(gold))
    # a card emblem in a gold frame
    d.rectangle([11, 8, 20, 22], fill=rgba((36, 22, 48)), outline=rgba(gold))
    d.ellipse([13, 12, 18, 17], outline=rgba(gold))
    save(c, 'binder3d_cover')
    sp = Image.new('RGBA', (16, 16), rgba(shade(leather, 0.8)))
    d = ImageDraw.Draw(sp)
    d.rectangle([0, 0, 15, 15], outline=rgba(dark))
    for y in (3, 7, 11):
        d.line([(0, y), (15, y)], fill=rgba(dark))
    save(sp, 'binder3d_spine')
    pg = Image.new('RGBA', (16, 16), rgba((238, 232, 214)))
    d = ImageDraw.Draw(pg)
    for x in range(0, 16, 2):
        d.line([(x, 0), (x, 15)], fill=rgba((200, 192, 170)))
    save(pg, 'binder3d_pages')
    r = Image.new('RGBA', (16, 16), rgba(gold))
    ImageDraw.Draw(r).rectangle([0, 0, 15, 15], outline=rgba(shade(gold, 0.6)))
    save(r, 'binder3d_ring')


def binder_model():
    T = "mtgcraft:item/"
    els = [
        # back cover, pages, front cover (lying flat, spine on the west side)
        box([3, 0, 2], [14, 1, 14], all_faces("#cover")),
        box([3.5, 1, 2.5], [13.5, 3, 13.5], {
            "north": face("#pages"), "south": face("#pages"), "east": face("#pages"), "up": face("#pages")}),
        box([3, 3, 2], [14, 4, 14], all_faces("#cover")),
        # the spine
        box([2, 0, 2], [3, 4, 14], all_faces("#spine")),
    ]
    for z in (4, 7.5, 11):  # three binder rings
        els.append(box([2.3, 1, z], [3.7, 3, z + 1.2], all_faces("#ring")))
    return {
        "textures": {"cover": T + "binder3d_cover", "spine": T + "binder3d_spine", "pages": T + "binder3d_pages",
                     "ring": T + "binder3d_ring", "particle": T + "binder3d_cover"},
        "gui_light": "side",
        "elements": els,
        "display": {
            "gui": {"rotation": [35, 210, 0], "translation": [0, 2, 0], "scale": [1.0, 1.0, 1.0]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [-90, 0, 0], "translation": [0, 0, -2], "scale": [1, 1, 1]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.5, 0.5, 0.5]},
            "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.5, 0.5, 0.5]},
            "firstperson_righthand": {"rotation": [10, 45, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
            "firstperson_lefthand": {"rotation": [10, 225, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
        },
    }


def write(name, model):
    with open(f'{MOD}/{name}.json', 'w') as f:
        json.dump(model, f, indent=1)


def main():
    os.makedirs(TEX, exist_ok=True)
    cards_texture()
    deck_box_textures('deckbox3d', (43, 66, 120), (20, 30, 60), (80, 110, 180), blue_emblem)
    deck_box_textures('udeckbox3d', (90, 44, 130), (40, 20, 60), (150, 90, 200), mana_emblem)
    gauntlet_textures()
    write('deck_box', deck_box_model('deckbox3d'))
    write('universal_deck_box', deck_box_model('udeckbox3d'))
    write('duel_gauntlet', gauntlet_model())
    binder_textures()
    write('binder', binder_model())
    print('Wrote deck box, universal deck box, gauntlet and binder models.')


if __name__ == '__main__':
    main()
