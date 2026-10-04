"""Generates MTGCraft's pack wrapper, booster box and card item textures (original pixel art).

Run from the mtgcraft folder:  python tools/make_pack_art.py
"""
import os
import random
from PIL import Image, ImageDraw

OUT = 'src/main/resources/assets/mtgcraft/textures'
random.seed(11)

# 8x8 icons. '.' = transparent; other letters map to the palette given with each icon.
ICONS = {
    'CREEPER': (["GGGGGGGG", "GGGGGGGG", "GKKGGKKG", "GKKGGKKG", "GGGKKGGG", "GGKKKKGG", "GGKKKKGG", "GGKGGKGG"],
                {'G': (94, 178, 72), 'K': (20, 28, 18)}),
    'UNDEAD': (["TTTTTTTT", "TTTTTTTT", "TKKTTKKT", "TWKTTWKT", "TTTTTTTT", "TTTDDTTT", "TDDTTDDT", "TTTTTTTT"],
               {'T': (64, 140, 104), 'K': (16, 32, 24), 'W': (220, 230, 220), 'D': (40, 92, 66)}),
    'NIGHT': (["KKKKKKKK", "KKKKKKKK", "KRKRRKRK", "KKKKKKKK", "KRRKKRRK", "KRRKKRRK", "KKKKKKKK", "KKKKKKKK"],
              {'K': (34, 30, 36), 'R': (220, 40, 40)}),
    'NETHER': (["....Y...", "...YO...", "..YOOY..", ".YORROY.", ".ORRRRO.", "ORRDDRRO", "RRDDDDRR", "DDDDDDDD"],
               {'Y': (255, 230, 90), 'O': (250, 150, 40), 'R': (210, 60, 30), 'D': (110, 30, 30)}),
    'END': ([".GGGGGG.", "GTTTTTTG", "GTCCCCTG", "GTCKKCTG", "GTCKKCTG", "GTCCCCTG", "GTTTTTTG", ".GGGGGG."],
            {'G': (40, 90, 70), 'T': (60, 160, 130), 'C': (120, 220, 180), 'K': (10, 20, 18)}),
    'OCEAN': (["........", "..OOOO..", ".OWOOOOO", "OOKOOOOO", "OOOOOOOO", ".OOOOOOO", "..OOOO.O", "........"],
              {'O': (240, 150, 50), 'W': (255, 255, 255), 'K': (20, 20, 20)}),
    'VILLAGE': (["BBBBBBBB", "SSSSSSSS", "SWKSSKWS", "SSSNNSSS", "SSSNNSSS", "SSSNNSSS", "SSSSSSSS", "SSSSSSSS"],
                {'B': (90, 60, 40), 'S': (190, 140, 110), 'W': (240, 240, 240), 'K': (60, 140, 60), 'N': (160, 110, 80)}),
    'RAID': (["WWWWWWWW", "WKKKKKKW", "WKWWWWKW", "WWKKKKWW", "WWWKKWWW", "WWKWWKWW", "WKWWWWKW", "WWWWWWWW"],
             {'W': (235, 235, 230), 'K': (30, 30, 30)}),
    'WITCH': (["...PP...", "...PPG..", "..PPPP..", "..PPPP..", ".PPPPPP.", "PPPPPPPP", "...SS...", "..SKKS.."],
              {'P': (100, 50, 130), 'G': (80, 200, 80), 'S': (160, 180, 120), 'K': (40, 40, 40)}),
    'SCULK': (["DDDDDDDD", "DCDDDDCD", "DDDTDDDD", "DDTCTDDD", "DDDTDDCD", "DCDDDDDD", "DDDDCDDD", "DDDDDDDD"],
              {'D': (12, 40, 48), 'T': (20, 90, 100), 'C': (80, 230, 240)}),
    'OVERWORLD': (["GGGGGGGG", "GgGGgGGg", "gDgDDgDg", "DDDDDDDD", "DdDDDDdD", "DDDDdDDD", "DDdDDDDD", "DDDDDDDD"],
                  {'G': (100, 180, 60), 'g': (70, 140, 40), 'D': (130, 90, 60), 'd': (100, 70, 45)}),
    'ENDER_DRAGON': (["K......K", "KK....KK", "KKKKKKKK", "KPKKKKPK", "KKKKKKKK", "KKGGGGKK", ".KKKKKK.", "..KKKK.."],
                     {'K': (24, 20, 30), 'P': (200, 80, 230), 'G': (60, 60, 70)}),
    'WITHER': ([".GGGGGG.", "GGGGGGGG", "GKKGGKKG", "GKKGGKKG", "GGGKKGGG", "GKGKGKGG", ".GGGGGG.", "..GGGG.."],
               {'K': (20, 20, 24), 'G': (110, 110, 118)}),
    'SET': (["...YY...", "...YY...", "YYYYYYYY", ".YYYYYY.", "..YYYY..", ".YY..YY.", "YY....YY", "........"],
            {'Y': (240, 200, 80)}),
}

# Wrapper colours: (main, dark, light)
WRAP = {
    'CREEPER': ((70, 150, 60), (30, 80, 30), (150, 220, 120)),
    'UNDEAD': ((40, 90, 80), (20, 45, 40), (110, 170, 150)),
    'NIGHT': ((45, 35, 70), (20, 15, 35), (110, 90, 160)),
    'NETHER': ((150, 40, 30), (70, 15, 15), (240, 120, 60)),
    'END': ((30, 25, 50), (10, 8, 22), (190, 200, 140)),
    'OCEAN': ((30, 90, 170), (15, 40, 90), (110, 190, 240)),
    'VILLAGE': ((150, 110, 70), (80, 55, 30), (230, 200, 140)),
    'RAID': ((70, 70, 80), (30, 30, 36), (170, 170, 180)),
    'WITCH': ((80, 40, 110), (35, 15, 55), (170, 110, 210)),
    'SCULK': ((15, 60, 70), (5, 25, 30), (60, 200, 210)),
    'OVERWORLD': ((60, 140, 220), (30, 80, 140), (160, 220, 255)),
    'ENDER_DRAGON': ((30, 20, 45), (10, 5, 18), (200, 90, 240)),
    'WITHER': ((40, 40, 46), (15, 15, 18), (130, 130, 140)),
    'SET': ((170, 40, 40), (90, 20, 20), (240, 200, 90)),
}


def icon(name, scale):
    rows, pal = ICONS[name]
    img = Image.new('RGBA', (8 * scale, 8 * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != '.':
                d.rectangle([x * scale, y * scale, x * scale + scale - 1, y * scale + scale - 1], fill=pal[ch] + (255,))
    return img


def wrapper(name, w, h, big):
    main, dark, light = WRAP[name]
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    crimp = max(1, h // 16)
    # body with a vertical gradient
    for y in range(crimp, h - crimp):
        t = (y - crimp) / max(1, h - 2 * crimp - 1)
        c = tuple(int(main[i] * (1 - t * 0.35) + dark[i] * t * 0.35) for i in range(3))
        d.line([(0, y), (w - 1, y)], fill=c + (255,))
    # crimped ends (zigzag)
    step = max(1, w // 8)
    for x in range(0, w, step):
        d.rectangle([x, 0, x + step // 2, crimp - 1], fill=light + (255,))
        d.rectangle([x + step // 2, h - crimp, x + step - 1, h - 1], fill=light + (255,))
    d.line([(0, crimp), (w - 1, crimp)], fill=light + (255,))
    d.line([(0, h - crimp - 1), (w - 1, h - crimp - 1)], fill=light + (255,))
    # edges
    d.line([(0, crimp), (0, h - crimp)], fill=dark + (255,))
    d.line([(w - 1, crimp), (w - 1, h - crimp)], fill=dark + (255,))
    # foil sheen stripe
    for i in range(0, w + h, max(6, w // 3)):
        for k in range(max(1, w // 16)):
            d.line([(i + k - h, h), (i + k, 0)], fill=(255, 255, 255, 38))
    # icon
    scale = (w * 5 // 8) // 8 if big else 1
    ic = icon(name, max(1, scale))
    if big:
        pad = 3
        box = [(w - ic.width) // 2 - pad, h * 2 // 5 - ic.height // 2 - pad,
               (w + ic.width) // 2 + pad - 1, h * 2 // 5 + ic.height // 2 + pad - 1]
        d.rectangle(box, fill=dark + (255,), outline=light + (255,))
        img.alpha_composite(ic, ((w - ic.width) // 2, h * 2 // 5 - ic.height // 2))
        # "MTG" style band near the bottom for the title (drawn in game)
        d.rectangle([2, h * 3 // 4 - 6, w - 3, h * 3 // 4 + 6], fill=dark + (220,))
    else:
        img.alpha_composite(ic, ((w - 8) // 2, (h - 8) // 2))
    return img


def item_pack(name):
    # 16x16: a 12x16 wrapper centred
    base = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    base.alpha_composite(wrapper(name, 12, 16, False), (2, 0))
    return base


def item_box(name):
    main, dark, light = WRAP[name]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([1, 4, 14, 14], fill=main + (255,), outline=dark + (255,))
    d.rectangle([1, 2, 14, 5], fill=light + (255,), outline=dark + (255,))
    d.line([(1, 9), (14, 9)], fill=dark + (255,))
    ic = icon(name, 1)
    img.alpha_composite(ic.resize((6, 6), Image.NEAREST), (5, 7))
    return img


def card_item():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([3, 1, 12, 14], fill=(58, 34, 78, 255), outline=(201, 162, 72, 255))
    d.ellipse([5, 5, 10, 10], fill=(36, 22, 48, 255), outline=(201, 162, 72, 255))
    return img


if __name__ == '__main__':
    os.makedirs(OUT + '/item', exist_ok=True)
    os.makedirs(OUT + '/gui/wrapper', exist_ok=True)
    for name in ICONS:
        item_pack(name).save(f"{OUT}/item/pack_{name.lower()}.png")
        wrapper(name, 64, 112, True).save(f"{OUT}/gui/wrapper/{name.lower()}.png")
    for name in ('ENDER_DRAGON', 'WITHER', 'SET'):
        item_box(name).save(f"{OUT}/item/box_{name.lower()}.png")
    card_item().save(f"{OUT}/item/card.png")
    print('ok', len(ICONS), 'themes')
