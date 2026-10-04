"""Writes the GameTest arena structure (a 9x5x9 box with a stone floor) as gzipped NBT, without extra libraries.

Run from the mtgcraft folder:  python tools/make_test_arena.py
"""
import gzip
import struct

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def name(n):
    b = n.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def t_int(n, v):
    return bytes([TAG_INT]) + name(n) + struct.pack('>i', v)


def t_string(n, v):
    return bytes([TAG_STRING]) + name(n) + name(v)


def t_list(n, elem_type, payloads):
    return bytes([TAG_LIST]) + name(n) + bytes([elem_type]) + struct.pack('>i', len(payloads)) + b''.join(payloads)


def t_compound(n, children):
    return bytes([TAG_COMPOUND]) + name(n) + b''.join(children) + bytes([TAG_END])


def compound_payload(children):
    return b''.join(children) + bytes([TAG_END])


def int_payload(v):
    return struct.pack('>i', v)


W, H, D = 9, 5, 9
blocks = []
for x in range(W):
    for z in range(D):
        pos = t_list('pos', TAG_INT, [int_payload(x), int_payload(0), int_payload(z)])
        blocks.append(compound_payload([pos, t_int('state', 0)]))
palette = [compound_payload([t_string('Name', 'minecraft:stone')])]
root = t_compound('', [
    t_int('DataVersion', 3465),
    t_list('size', TAG_INT, [int_payload(W), int_payload(H), int_payload(D)]),
    t_list('palette', TAG_COMPOUND, palette),
    t_list('blocks', TAG_COMPOUND, blocks),
    t_list('entities', TAG_COMPOUND, []),
])
out = 'src/main/resources/data/mtgcraft/structures/arena.nbt'
import os
os.makedirs(os.path.dirname(out), exist_ok=True)
with gzip.open(out, 'wb') as f:
    f.write(root)
print('wrote', out)
