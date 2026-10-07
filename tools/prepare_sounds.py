"""Builds the sounds of Hoarfrost from CC0 field recordings on Freesound.

Usage: python tools/prepare_sounds.py   (from the mod folder; needs numpy, scipy, soundfile)

The recordings (all Creative Commons 0, see SOURCES) are downloaded into tools/.cache (not committed).

* Wind loops: the steadiest stretch of a recording, rumble removed, made into a seamless mono loop (the game places
  the wind upwind of the listener).
* Gusts: the loudest swells of the storm recordings, cut with a natural fade in and out.
* Footsteps: single steps found as clean onsets in recordings of walking in snow.

Every file is normalized to a loudness that encodes how loud that sound is meant to be relative to the others.
"""
import math
import os
import re
import urllib.request

import numpy as np
import soundfile as sf
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "hoarfrost", "sounds")
RATE = 44100

# Freesound id -> (author, title). All CC0. Listed in the README credits.
SOURCES = {
    # Wind.
    473815: ("guyburns", "Wind during snow storm #1"),
    574552: ("TRP", "Winter storm wind lashing snow"),
    181252: ("mario1298", "a gentle breeze, wind 3"),
    # Footsteps in fresh snow.
    432285: ("Nimlos", "snow footsteps (outside recording)"),
    453168: ("florianreichelt", "footsteps in fresh snow"),
    378056: ("bajko", "sfx_snow_footstep-01"),
    # Footsteps on packed snow.
    333422: ("MDRivet", "Snow Walking Quiet"),
    559459: ("mshahen", "Footsteps or Walking in Hard Crunchy Snow"),
}


def fetch(url, binary=False):
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    data = urllib.request.urlopen(request, timeout=120).read()
    return data if binary else data.decode("utf-8", "replace")


def source(sound_id):
    """The recording as a mono signal at RATE."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f"{sound_id}.ogg")
    if not os.path.exists(path):
        page = fetch(f"https://freesound.org/s/{sound_id}/")
        if "publicdomain/zero" not in page:
            raise RuntimeError(f"Freesound {sound_id} is not CC0")
        preview = re.search(r"https://cdn.freesound.org/previews/\d+/\d+_\d+-hq\.mp3", page).group(0)
        with open(path, "wb") as f:
            f.write(fetch(preview[:-4] + ".ogg", True))
    x, rate = sf.read(path, always_2d=True)
    x = x.mean(axis=1)
    if rate != RATE:
        x = signal.resample_poly(x, RATE, rate)
    return x


def filt(x, kind, cut, order=2):
    sos = signal.butter(order, cut, btype=kind, fs=RATE, output="sos")
    return signal.sosfiltfilt(sos, x, axis=0)


def soften(x, rumble_cut=90.0, air_cut=8000.0, air_cut_amount=0.35, lowpass=None):
    x = filt(x, "high", rumble_cut)
    x = x - filt(x, "high", air_cut) * air_cut_amount
    if lowpass:
        x = filt(x, "low", lowpass, order=4)
    return x


def loudness_db(x, hop):
    frames = len(x) // hop
    return np.array([10 * np.log10(np.mean(x[i * hop:(i + 1) * hop] ** 2) + 1e-12) for i in range(frames)])


def steadiest(x, seconds, window=None, avoid=None):
    """Start of the stretch of {seconds} whose loudness varies least (no thunder, cars or gusts).

    {window} limits the search to (start, end) seconds of the recording; {avoid} is a start to keep away from.
    """
    hop = RATE // 2
    db = loudness_db(x, hop)
    span = int(seconds * 2)
    first, last = 0, len(db) - span
    if window is not None:
        first = max(0, int(window[0] * 2))
        last = min(last, int(window[1] * 2) - span)
    best, best_score = first, float("inf")
    for start in range(first, max(first + 1, last)):
        if avoid is not None and abs(start * hop - avoid) < span * hop:
            continue
        frame = db[start:start + span]
        if len(frame) == 0:
            break
        score = np.std(frame) + 0.5 * (frame.max() - np.median(frame))
        if score < best_score:
            best, best_score = start, score
    return best * hop


def make_loop(x, seconds, fade, start):
    n = int(seconds * RATE)
    f = int(fade * RATE)
    take = x[start:start + n + f]
    out = take[:n].copy()
    t = np.linspace(0, math.pi / 2, f)
    out[:f] = take[:f] * np.sin(t) + take[n:n + f] * np.cos(t)
    return out


def normalize(x, rms_db):
    x = x * (10 ** (rms_db / 20) / (np.sqrt(np.mean(x ** 2)) + 1e-12))
    peak = np.max(np.abs(x))
    return x * (0.95 / peak) if peak > 0.95 else x


def write(name, data, rate=RATE):
    path = os.path.join(OUT, name + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if rate != RATE:
        data = signal.resample_poly(data, rate, RATE)
    data = data.astype(np.float32)
    # libsndfile crashes on large single Vorbis writes; feed it in blocks.
    with sf.SoundFile(path, "w", rate, 1, format="OGG", subtype="VORBIS") as out:
        for start in range(0, len(data), 8192):
            out.write(data[start:start + 8192])


def loops_from(sound_id, name, seconds, rms_db, variants=2, window=None, rate=RATE, **soften_args):
    """Mono loops {name}_a and {name}_b (or just {name}) from different stretches of the recording."""
    x = soften(source(sound_id), **soften_args)
    length = (window[1] - window[0]) if window else len(x) / RATE
    if variants == 1:
        seconds = min(seconds, length - 4.0)
        write(name, normalize(make_loop(x, seconds, 3.0, steadiest(x, seconds + 3.0, window)), rms_db), rate)
        return
    if (length - 8.0) / 2 < seconds * 0.75:
        # A short recording: one loop as long as it allows, the second variant is the same loop started half way.
        seconds = min(seconds, length - 4.0)
        loop = normalize(make_loop(x, seconds, 3.0, steadiest(x, seconds + 3.0, window)), rms_db)
        write(f"{name}_a", loop, rate)
        write(f"{name}_b", np.roll(loop, len(loop) // 2), rate)
        return
    seconds = min(seconds, (length - 8.0) / 2)
    first = steadiest(x, seconds + 3.0, window)
    second = steadiest(x, seconds + 3.0, window, avoid=first)
    for suffix, start in (("a", first), ("b", second)):
        write(f"{name}_{suffix}", normalize(make_loop(x, seconds, 3.0, start), rms_db), rate)


def gusts(sound_ids, name, count, seconds=4.5, rms_db=-20):
    """The loudest swells of storm recordings, each from its rise to its fall, as {name}{n}."""
    found = []
    for sound_id in sound_ids:
        x = soften(source(sound_id), rumble_cut=40.0, air_cut=5000.0, air_cut_amount=0.4)
        envelope = filt(np.abs(x), "low", 1.5)
        peaks, props = signal.find_peaks(envelope, distance=int(seconds * RATE), prominence=np.median(envelope) * 0.4)
        for peak, prominence in zip(peaks, props["prominences"]):
            found.append((prominence / (np.median(envelope) + 1e-9), sound_id, peak, x))
    found.sort(key=lambda item: -item[0])
    for index, (_, sound_id, peak, x) in enumerate(found[:count], start=1):
        start = max(0, peak - int(seconds * 0.55 * RATE))
        take = x[start:start + int(seconds * RATE)].copy()
        window = np.sin(np.linspace(0, math.pi, len(take))) ** 1.3
        write(f"{name}{index}", normalize(take * window, rms_db))


def steps(sound_ids, name, count, highpass=90.0, rms_db=-17, length=0.32):
    """Single footsteps: the clearest onsets of walking recordings, each cut short with a quick fade, as {name}{n}."""
    found = []
    for sound_id in sound_ids:
        x = filt(source(sound_id), "high", highpass)
        envelope = filt(np.abs(x), "low", 25.0)
        peaks, props = signal.find_peaks(envelope, distance=int(0.35 * RATE), prominence=np.percentile(envelope, 90) * 0.5)
        for peak, prominence in zip(peaks, props["prominences"]):
            floor = np.percentile(envelope[max(0, peak - RATE):peak + RATE], 20) + 1e-9
            found.append((prominence / floor, sound_id, peak, x))
    found.sort(key=lambda item: -item[0])
    used = []
    index = 1
    for _, sound_id, peak, x in found:
        if index > count:
            break
        if any(s == sound_id and abs(p - peak) < RATE // 2 for s, p in used):
            continue
        used.append((sound_id, peak))
        start = max(0, peak - int(0.07 * RATE))
        step = x[start:start + int(length * RATE)].copy()
        fade = np.ones(len(step))
        tail = int(0.1 * RATE)
        fade[-tail:] = np.linspace(1, 0, tail) ** 2
        fade[:int(0.004 * RATE)] = np.linspace(0, 1, int(0.004 * RATE))
        write(f"{name}{index}", normalize(step * fade, rms_db))
        index += 1


def clean(folder):
    path = os.path.join(OUT, folder)
    if os.path.isdir(path):
        for old in os.listdir(path):
            os.remove(os.path.join(path, old))


def main():
    for folder in ("wind", "step"):
        clean(folder)
    # The roar of a snowstorm (two recordings, so the loop does not repeat audibly) and a soft breeze.
    loops_from(473815, "wind/strong_a", 24, -20, variants=1, rumble_cut=40.0, air_cut=6000.0, air_cut_amount=0.3, rate=32000)
    loops_from(574552, "wind/strong_b", 24, -20, variants=1, rumble_cut=40.0, air_cut=6000.0, air_cut_amount=0.3, rate=32000)
    loops_from(181252, "wind/light", 24, -24, variants=2, rumble_cut=60.0, air_cut=5000.0, air_cut_amount=0.4, rate=32000)
    gusts([473815, 574552], "wind/gust", 5)
    steps([432285, 453168, 378056], "step/powder", 8)
    steps([333422, 559459], "step/packed", 8)


if __name__ == "__main__":
    main()
