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


# ---- more vanilla themes
ICONS.update({
    'CELESTIAL': (["..YYYY..", ".Y....Y.", "..YYYY..", "W..SS..W", "WW.SS.WW", "WWWSSWWW", ".WWSSWW.", "...SS..."],
                  {'Y': (255, 220, 90), 'W': (245, 245, 255), 'S': (120, 200, 240)}),
    'HIVE': (["...KK...", "..WWWW..", ".YKYKYK.", "YKYKYKYK", "YKYKYKYK", ".YKYKYK.", "..K..K..", "........"],
             {'Y': (240, 200, 40), 'K': (40, 30, 20), 'W': (220, 240, 255)}),
    'GOLEM': (["IIIIIIII", "IIIIIIII", "IKIIIIKI", "IIIRRIII", "IIIRRIII", "IIIIIIII", "VIIIIIIV", "VVIIIIVV"],
              {'I': (200, 195, 185), 'K': (60, 40, 30), 'R': (170, 110, 90), 'V': (90, 140, 60)}),
    'PIGLIN': (["PPPPPPPP", "PPPPPPPP", "PKWPPWKP", "PPPPPPPP", "PPNNNNPP", "PGNKKNGP", "PGPPPPGP", "PPPPPPPP"],
               {'P': (230, 160, 150), 'K': (30, 20, 20), 'W': (240, 240, 240), 'N': (240, 190, 180), 'G': (240, 200, 60)}),
    'INFERNO': (["YYYYYYYY", "YOOOOOOY", "YOKOOKOY", "YOOOOOOY", "..O..O..", ".O.OO.O.", "O..OO..O", ".O....O."],
                {'Y': (255, 230, 90), 'O': (240, 140, 30), 'K': (60, 20, 10)}),
    'SOUL': (["WWWWWWWW", "WWWWWWWW", "WKKWWKKW", "WWWWWWWW", "WWKKKKWW", "WWWWWWWW", "W.W.W.W.", ".W.W.W.W"],
             {'W': (235, 235, 235), 'K': (60, 60, 70)}),
    'SLIME': (["GGGGGGGG", "GLLLLLLG", "GLKLLKLG", "GLKLLKLG", "GLLLLLLG", "GLLKKLLG", "GLLLLLLG", "GGGGGGGG"],
              {'G': (90, 170, 80), 'L': (130, 210, 110), 'K': (30, 60, 30)}),
    'MONUMENT': (["P.P..P.P", ".PTTTTP.", "PTTTTTTP", "TTWWWWTT", "TTWOOWTT", "PTTTTTTP", ".PTTTTP.", "P.P..P.P"],
                 {'P': (230, 140, 60), 'T': (70, 160, 150), 'W': (240, 240, 240), 'O': (230, 120, 40)}),
    'FROST': (["...W....", ".W.W.W..", "..WWW...", "WWWCWWW.", "..WWW...", ".W.W.W..", "...W....", "........"],
              {'W': (230, 245, 255), 'C': (120, 200, 255)}),
    'DESERT': (["........", "..S.....", ".SSS..S.", "..S..SSS", "..S...S.", "YYYYYYYY", "YyYYYyYY", "YYYYYYYY"],
               {'S': (90, 160, 70), 'Y': (230, 210, 150), 'y': (200, 180, 120)}),
    'JUNGLE': (["YYYYYYYY", "YKYYYYKY", "YYGYYGYY", "YYYYYYYY", "YYYKKYYY", "YKYKKYKY", "YYYYYYYY", "Y.KY.YK."],
               {'Y': (230, 190, 90), 'K': (60, 40, 20), 'G': (80, 160, 60)}),
    'SWAMP': (["........", ".GG..GG.", "GKWGGWKG", "GGGGGGGG", "GRRRRRRG", "GGGGGGGG", ".GG..GG.", "........"],
              {'G': (200, 120, 50), 'K': (20, 20, 20), 'W': (240, 240, 240), 'R': (150, 60, 40)}),
    'WILD': (["O......O", "OO....OO", "OOOOOOOO", "OKOOOOKO", "OOWWWWOO", "WWWKKWWW", ".WWWWWW.", "..WWWW.."],
             {'O': (220, 120, 40), 'K': (30, 20, 20), 'W': (245, 240, 230)}),
    'FARM': (["WWKKWWKK", "WWWWWWWW", "WKWWWWKW", "WWWWWWWW", "WWPPPPWW", "WPKPPKPW", "WWPPPPWW", "WWWWWWWW"],
             {'W': (240, 240, 240), 'K': (40, 40, 40), 'P': (230, 160, 170)}),
    'STABLE': (["...BBB..", "..BBBBB.", ".BBKBBB.", "BBBBBBB.", "BBB.BBM.", "BB..BMM.", "....MM..", "........"],
               {'B': (140, 90, 50), 'K': (20, 20, 20), 'M': (60, 40, 25)}),
    'CAVE': (["SSSSSSSS", "SsSSDSSS", "SSSSSSsS", "SDSSsSSS", "SSSSSSDS", "SsSSSSSS", "SSSDSSSs", "SSSSSSSS"],
             {'S': (110, 110, 110), 's': (80, 80, 80), 'D': (90, 230, 230)}),
    'ANCIENT': (["........", "...GG...", "..GKKG..", ".GGGGGG.", "GGRGGRGG", "GGGGGGGG", ".GGGGGG.", "..T..T.."],
                {'G': (160, 70, 60), 'K': (60, 20, 20), 'R': (90, 170, 80), 'T': (90, 60, 40)}),
    'MUSHROOM': ([".RRRRRR.", "RRWRRWRR", "RRRRRRRR", "RWRRRRWR", ".RRRRRR.", "...SS...", "...SS...", "..SSSS.."],
                 {'R': (200, 40, 40), 'W': (245, 245, 245), 'S': (230, 220, 200)}),
    'END_CITY': (["PPPPPPPP", "PppppppP", "PPPPPPPP", "PYYYYYYP", "PYKYYKYP", "PYYYYYYP", "PPPPPPPP", "PppppppP"],
                 {'P': (160, 110, 160), 'p': (120, 80, 120), 'Y': (230, 230, 170), 'K': (40, 40, 40)}),
    'ARCHER': (["....B...", "...B.S..", "..B..S..", ".B...SAA", "..B..S..", "...B.S..", "....B...", "........"],
               {'B': (140, 90, 50), 'S': (220, 220, 220), 'A': (180, 180, 190)}),
})

# ---- All the Mods add-on themes
ICONS.update({
    'TWILIGHT': (["...F....", "..FFF...", ".FFFFF..", "..FFF.Y.", ".FFFFF..", "FFFFFFF.", "...T....", "...T...."],
                 {'F': (40, 110, 70), 'Y': (240, 240, 140), 'T': (90, 60, 40)}),
    'NAGA': (["..GGGG..", ".GKGGKG.", ".GGGGGG.", "..GRRG..", "...GG...", "..GG....", ".GG.GGG.", "..GGG..."],
             {'G': (90, 150, 60), 'K': (240, 220, 60), 'R': (200, 40, 40)}),
    'HYDRA': (["G..G..G.", "GG.GG.GG", ".G..G..G", ".G..G.G.", "..GGGGG.", "..GGGGG.", "...GGG..", "..G...G."],
              {'G': (110, 90, 60)}),
    'CATACLYSM': (["O.O..O.O", "OOOOOOOO", "KKOKKOKK", "KKKKKKKK", "KOOKKOOK", "KKKKKKKK", "K.KKKK.K", "K......K"],
                  {'O': (240, 120, 30), 'K': (50, 40, 40)}),
    'ABYSS': (["...TT...", "..TTTT..", ".TKTTKT.", ".TTTTTT.", "T.T.T.T.", "T.T.T.T.", ".T.T.T.T", "..T...T."],
              {'T': (60, 90, 160), 'K': (220, 240, 255)}),
    'WILDLIFE': (["..BBBB..", ".BBBBBB.", "BKBBBBKB", "BBBBBBBB", "BBWBBWBB", ".BBNNBB.", "..BNNB..", "...NN..."],
                 {'B': (140, 120, 90), 'K': (20, 20, 20), 'W': (240, 240, 230), 'N': (90, 70, 50)}),
    'MOWZIE': (["W.WWWW.W", "WWWWWWWW", "WKKWWKKW", "WWWWWWWW", "WRWRRWRW", "WWWWWWWW", "W.W..W.W", "........"],
               {'W': (210, 200, 170), 'K': (30, 20, 20), 'R': (200, 60, 40)}),
    'ARCANE': (["...PP...", "..PPPP..", ".PPPPPP.", "PPPYYPPP", "..PPPP..", "..BBBB..", ".BBBBBB.", "BBBBBBBB"],
               {'P': (140, 80, 220), 'Y': (250, 230, 120), 'B': (70, 40, 120)}),
    'MANA': (["...G....", "..GGG...", ".GGWGG..", "GGWBWGG.", ".GGWGG..", "..GGG...", "...G....", "........"],
             {'G': (90, 200, 120), 'W': (230, 255, 240), 'B': (90, 160, 240)}),
    'UNDERGARDEN': (["..PPPP..", ".PPGPPP.", "PPPPPGPP", "..SSSS..", "...SS...", "...SS...", "..SSSS..", "DDDDDDDD"],
                    {'P': (120, 70, 140), 'G': (160, 230, 120), 'S': (200, 200, 180), 'D': (60, 50, 50)}),
    'OTHERSIDE': (["KKKKKKKK", "KCKKKKCK", "KKKKKKKK", "KKCCCCKK", "KCCKKCCK", "KKKKKKKK", "KCKKKKCK", "KKKKKKKK"],
                  {'K': (16, 22, 30), 'C': (80, 220, 240)}),
    'OCCULT': (["...RR...", "..R..R..", ".R.RR.R.", "R.R..R.R", "R.R..R.R", ".R.RR.R.", "..R..R..", "...RR..."],
               {'R': (200, 30, 40)}),
    'CHAOS': (["...GG...", ".OOOOOO.", "OOOOOOOO", "OKOOOOKO", "OOOKKOOO", "OKOKKOKO", "OOOOOOOO", ".OOOOOO."],
              {'G': (60, 140, 40), 'O': (230, 120, 30), 'K': (40, 20, 10)}),
    'SPELLBOOK': (["BBBBBBB.", "BYYYYYB.", "BYBBBYB.", "BYBRBYB.", "BYBBBYB.", "BYYYYYB.", "BBBBBBB.", ".WWWWWW."],
                  {'B': (60, 40, 110), 'Y': (240, 200, 80), 'R': (200, 60, 200), 'W': (230, 230, 220)}),
    'STARBOUND': (["....W...", "...WWW..", "...WBW..", "...WWW..", "..WWWWW.", ".RWWWWWR", ".R.OO..R", "...O...."],
                  {'W': (230, 230, 240), 'B': (80, 160, 240), 'R': (200, 50, 50), 'O': (250, 160, 40)}),
    'CHAMPION': (["Y.Y..Y.Y", "YYYYYYYY", "YRYYYYRY", "YYYYYYYY", "........", ".SSSSSS.", ".S.SS.S.", "..SSSS.."],
                 {'Y': (245, 200, 60), 'R': (200, 40, 40), 'S': (180, 180, 190)}),
    'DRAGONFIRE': (["R......R", "RR.RR.RR", "RRRRRRRR", "RYRRRRYR", "RRRRRRRR", ".RROORR.", "..OYYO..", "...OO..."],
                   {'R': (170, 30, 30), 'Y': (250, 220, 60), 'O': (250, 140, 30)}),
    'AETHER': (["........", ".WW..WW.", "WWWWWWWW", "WBWWWWBW", ".WWWWWW.", "..GGGG..", ".GGGGGG.", "........"],
               {'W': (250, 250, 255), 'B': (150, 200, 250), 'G': (240, 210, 80)}),
    'SKIES': (["BBBBBBBB", "BBWWBBBB", "BWWWWBBB", "BBBBBWWB", "BBBBWWWW", "BBBBBBBB", "BWWBBBBB", "WWWWBBBB"],
              {'B': (90, 150, 230), 'W': (240, 245, 255)}),
    'ALLTHEMODIUM': (["..YYYY..", ".YOOOOY.", "YOYYYYOY", "YOYPPYOY", "YOYPPYOY", "YOYYYYOY", ".YOOOOY.", "..YYYY.."],
                     {'Y': (250, 210, 60), 'O': (200, 130, 30), 'P': (170, 60, 220)}),
})

WRAP.update({
    'CELESTIAL': ((220, 210, 160), (150, 130, 70), (255, 250, 220)),
    'HIVE': ((220, 170, 40), (120, 80, 20), (255, 230, 120)),
    'GOLEM': ((150, 145, 135), (80, 75, 70), (220, 215, 205)),
    'PIGLIN': ((200, 140, 60), (110, 60, 30), (250, 210, 100)),
    'INFERNO': ((220, 110, 20), (120, 40, 10), (255, 210, 80)),
    'SOUL': ((70, 60, 50), (30, 25, 20), (90, 220, 230)),
    'SLIME': ((80, 160, 70), (35, 80, 30), (160, 230, 140)),
    'MONUMENT': ((60, 140, 130), (25, 70, 65), (150, 220, 210)),
    'FROST': ((150, 200, 240), (70, 110, 160), (235, 245, 255)),
    'DESERT': ((210, 180, 110), (130, 100, 50), (250, 235, 180)),
    'JUNGLE': ((50, 130, 50), (20, 70, 20), (160, 220, 90)),
    'SWAMP': ((70, 90, 50), (30, 40, 20), (150, 180, 90)),
    'WILD': ((170, 90, 40), (90, 45, 20), (240, 180, 110)),
    'FARM': ((200, 70, 60), (110, 30, 25), (250, 220, 150)),
    'STABLE': ((130, 90, 50), (70, 45, 20), (220, 190, 130)),
    'CAVE': ((90, 90, 95), (40, 40, 45), (100, 230, 230)),
    'ANCIENT': ((120, 150, 70), (60, 80, 30), (210, 220, 140)),
    'MUSHROOM': ((170, 40, 40), (90, 20, 20), (240, 230, 220)),
    'END_CITY': ((150, 100, 150), (80, 50, 80), (230, 230, 170)),
    'ARCHER': ((110, 130, 80), (55, 65, 35), (220, 220, 200)),
    'TWILIGHT': ((40, 70, 90), (15, 30, 40), (150, 230, 160)),
    'NAGA': ((70, 120, 50), (30, 60, 20), (220, 210, 80)),
    'HYDRA': ((110, 70, 40), (55, 30, 15), (230, 160, 80)),
    'CATACLYSM': ((60, 45, 45), (25, 15, 15), (250, 130, 40)),
    'ABYSS': ((25, 40, 90), (8, 15, 40), (100, 160, 240)),
    'WILDLIFE': ((150, 120, 70), (80, 60, 30), (230, 210, 150)),
    'MOWZIE': ((160, 60, 40), (80, 25, 15), (240, 200, 140)),
    'ARCANE': ((90, 50, 160), (40, 20, 80), (210, 170, 255)),
    'MANA': ((60, 160, 120), (25, 80, 60), (190, 250, 220)),
    'UNDERGARDEN': ((80, 60, 90), (35, 25, 40), (170, 230, 120)),
    'OTHERSIDE': ((15, 30, 40), (5, 10, 15), (80, 220, 240)),
    'OCCULT': ((70, 15, 25), (30, 5, 10), (230, 60, 70)),
    'CHAOS': ((90, 40, 20), (40, 15, 5), (240, 140, 40)),
    'SPELLBOOK': ((60, 40, 120), (25, 15, 55), (240, 200, 90)),
    'STARBOUND': ((20, 20, 50), (5, 5, 20), (200, 210, 255)),
    'CHAMPION': ((150, 30, 30), (70, 10, 10), (250, 210, 80)),
    'DRAGONFIRE': ((130, 20, 20), (60, 5, 5), (250, 170, 50)),
    'AETHER': ((170, 210, 240), (90, 130, 170), (255, 240, 170)),
    'SKIES': ((70, 130, 210), (30, 60, 120), (230, 240, 255)),
    'ALLTHEMODIUM': ((60, 30, 80), (25, 10, 35), (250, 210, 60)),
})


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
