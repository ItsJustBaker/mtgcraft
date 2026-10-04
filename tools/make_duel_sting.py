"""Synthesizes MTGCraft's original "duel start" sting (impact, riser, brass stab) and encodes it to OGG.

Run from the mtgcraft folder:  python tools/make_duel_sting.py
Needs numpy and imageio-ffmpeg (pip install numpy imageio-ffmpeg).
"""
import subprocess
import wave

import imageio_ffmpeg
import numpy as np

SR = 44100
OUT = 'src/main/resources/assets/mtgcraft/sounds/duel_start.ogg'


def env(n, attack, decay):
    t = np.arange(n) / SR
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    return a * np.exp(-t / decay)


def tone(freq, dur, harmonics=(1, 0.5, 0.33, 0.25, 0.2), detune=0.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for k, amp in enumerate(harmonics, start=1):
        for d in (-detune, detune):
            out += amp * np.sin(2 * np.pi * freq * k * (1 + d) * t)
    return out / (2 * sum(harmonics))


def note(freq):
    return 440.0 * 2 ** ((freq - 69) / 12)


total = int(3.2 * SR)
mix = np.zeros(total)

# 1) riser: filtered noise + rising sine sweep into the hit at 0.9 s
rise_n = int(0.9 * SR)
t = np.arange(rise_n) / SR
sweep = np.sin(2 * np.pi * (80 * t + 220 * t ** 2))
noise = np.random.default_rng(3).normal(0, 1, rise_n)
noise = np.convolve(noise, np.ones(40) / 40, mode='same')
riser = (0.35 * sweep + 0.25 * noise) * (t / t[-1]) ** 2
mix[:rise_n] += riser

hit = int(0.9 * SR)

# 2) impact: pitch-dropping sine boom + noise crack
boom_n = int(1.6 * SR)
tb = np.arange(boom_n) / SR
boom = np.sin(2 * np.pi * (55 + 90 * np.exp(-tb * 18)) * tb) * np.exp(-tb * 2.6)
crack = np.random.default_rng(5).normal(0, 1, boom_n) * np.exp(-tb * 30) * 0.6
mix[hit:hit + boom_n] += 1.1 * boom + crack

# 3) brass stab: a minor chord with a bright attack, then a second higher stab
def stab(at, root, dur, gain):
    n = int(dur * SR)
    chord = sum(tone(note(root + i), dur, detune=0.003) for i in (0, 3, 7, 12))
    e = env(n, 0.012, dur * 0.45)
    s = int(at * SR)
    mix[s:s + n] += gain * chord[:n] * e[:n]

stab(0.9, 50, 1.4, 1.0)   # D minor
stab(1.45, 53, 1.6, 0.9)  # F (rising answer)

# 4) cheap reverb: a few decaying echoes
dry = mix.copy()
for delay, g in ((0.045, 0.35), (0.083, 0.25), (0.131, 0.18), (0.197, 0.12)):
    d = int(delay * SR)
    mix[d:] += g * dry[:-d]

mix /= np.max(np.abs(mix)) * 1.05
pcm = (mix * 32767).astype(np.int16)

wav = 'build/duel_start.wav'
import os
os.makedirs('build', exist_ok=True)
with wave.open(wav, 'wb') as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
os.makedirs(os.path.dirname(OUT), exist_ok=True)
subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', '5', OUT], check=True)
print('wrote', OUT)
