"""GTSinger (CC BY-NC-SA 4.0; evaluation only, like CSD): picks songs and derives key labels from
their MusicXML scores, fetching only the scores first.

A clip is one singer's Control_Group (normal singing) of one song: its numbered segments
(phrases) joined in order with 0.4 s of silence between them. Label rule (fixed before any
scoring; derived labels, not annotations), from the notes of the segments' MusicXML scores:
  * the scores' own <key> elements are NOT used: they contradict the written notes in most
    clips (e.g. 4 flats over F-sharps), see the printed count;
  * the tonic is the clip's last note, which must also be the pitch class held longest (summed
    written duration);
  * the mode is the one whose scale covers at least 95 % of the written note time: the major
    scale, or the natural minor plus its raised seventh; if both or neither do, the clip is left out.
Output: OUT.json {clip: {key, fifths, mode_written, seconds, segments, why}} (key -1 = left out).

usage: python -I gts_select.py METADATA.json [METADATA2.json ...] --xml-dir DIR --out OUT.json [--fetch] [--prefilter]
       (--fetch downloads the MusicXML of every Control_Group segment it needs into DIR)
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict

BASE = "https://huggingface.co/datasets/GTSinger/GTSinger/resolve/main/"
MAJOR = (0, 2, 4, 5, 7, 9, 11)


def fetch(rel, dest):
    if os.path.exists(dest):
        return True
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    url = BASE + urllib.parse.quote(rel)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
            open(dest + ".part", "wb").write(data)
            os.replace(dest + ".part", dest)
            return True
        except Exception as e:  # noqa: BLE001
            if getattr(e, "code", None) == 404:
                return False
            time.sleep(2 + 3 * attempt)
    return False


STEP = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def xml_read(path):
    """(fifths list, modes list, notes [(midi, quarter-note duration)]) of one score."""
    import xml.etree.ElementTree as ET
    root = ET.parse(path).getroot()
    fifths = [int(e.text) for e in root.iter("fifths")]
    modes = [e.text.strip().lower() for e in root.iter("mode") if e.text]
    div = 1.0
    notes = []
    for part in root.iter("part"):
        for meas in part.iter("measure"):
            for el in meas:
                if el.tag == "attributes":
                    d = el.find("divisions")
                    if d is not None:
                        div = float(d.text)
                elif el.tag == "note":
                    p = el.find("pitch")
                    dur = el.find("duration")
                    if p is None or dur is None or el.find("chord") is not None:
                        continue
                    midi = 12 * (int(p.find("octave").text) + 1) + STEP[p.find("step").text] + int(float((p.find("alter").text if p.find("alter") is not None else "0") or 0))
                    d = float(dur.text) / div
                    if el.find("tie[@type='stop']") is not None and notes and notes[-1][0] == midi:
                        notes[-1] = (midi, notes[-1][1] + d)
                    else:
                        notes.append((midi, d))
    return fifths, modes, notes


def clips_from(meta_files):
    clips = defaultdict(list)
    for mf in meta_files:
        for x in json.load(open(mf)):
            lang, singer, tech, song, grp, idx = x["item_name"].split("#")
            if grp != "Control_Group":
                continue
            clips[(lang, singer, song)].append((tech, int(idx), x))
    out = {}
    for (lang, singer, song), segs in clips.items():
        # one technique folder's Control_Group per singer and song (the first, by name)
        tech = sorted({t for t, _, _ in segs})[0]
        segs = sorted([(i, x) for t, i, x in segs if t == tech], key=lambda s: s[0])
        out[f"{lang}#{singer}#{song}"] = dict(lang=lang, singer=singer, song=song, tech=tech, segs=[x for _, x in segs])
    return out


def notes_of(seg):
    """(pitch, duration) per note: consecutive phones with the same note are one note."""
    notes = []
    prev = None
    for p, d, t, w in zip(seg["ep_pitches"], seg["ep_notedurs"], seg["ep_types"], seg["ph2words"]):
        if p <= 0:
            prev = None
            continue
        key = (w, p, d)
        if key == prev:
            continue
        prev = key
        notes.append((p, d))
    return notes


def prefilter(notes):
    """The label rule on the metadata's notes (the same pitches as the scores'), to fetch only
    the scores of clips that can pass it."""
    if not notes:
        return False
    pcs = defaultdict(float)
    for p, d in notes:
        pcs[p % 12] += d
    total = sum(pcs.values())
    last = notes[-1][0] % 12
    if max(pcs, key=pcs.get) != last:
        return False
    cmaj = sum(pcs[(last + x) % 12] for x in MAJOR) / total
    cmin = sum(pcs[(last + x) % 12] for x in (0, 2, 3, 5, 7, 8, 10, 11)) / total
    return (cmaj >= 0.95) != (cmin >= 0.95)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("meta", nargs="+")
    ap.add_argument("--xml-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--fetch", action="store_true")
    ap.add_argument("--prefilter", action="store_true", help="skip clips whose metadata notes cannot pass the rule")
    a = ap.parse_args()
    clips = clips_from(a.meta)
    res = {}
    for name, c in sorted(clips.items()):
        notes = [n for s in c["segs"] for n in notes_of(s)]
        seconds = sum(sum(s["word_durs"]) for s in c["segs"]) + 0.4 * (len(c["segs"]) - 1)
        if a.prefilter and not prefilter(notes):
            continue
        fifths, modes, xnotes = set(), set(), []
        missing = 0
        for sg in c["segs"]:
            rel = sg["wav_fn"][:-4] + ".musicxml"
            dest = os.path.join(a.xml_dir, rel)
            if a.fetch:
                fetch(rel, dest)
            if not os.path.exists(dest):
                missing += 1
                continue
            f, m, n = xml_read(dest)
            fifths |= set(f)
            modes |= set(m)
            xnotes += n
        r = dict(key=-1, seconds=round(seconds, 2), segments=len(c["segs"]), fifths=sorted(fifths), modes=sorted(modes),
                 lang=c["lang"], singer=c["singer"], song=c["song"], tech=c["tech"],
                 wavs=[sg["wav_fn"] for sg in c["segs"]])
        if missing or not xnotes:
            r["why"] = f"missing {missing} scores" if missing else "no notes"
            res[name] = r
            continue
        pcs = defaultdict(float)
        for p, d in xnotes:
            pcs[p % 12] += d
        total = sum(pcs.values())
        held = max(pcs, key=pcs.get)
        last = xnotes[-1][0] % 12
        if len(fifths) == 1:
            do_sig = (7 * next(iter(fifths))) % 12
            best_do = max(range(12), key=lambda t: sum(pcs[(t + x) % 12] for x in MAJOR))
            r["signature_matches_notes"] = do_sig == best_do
        if held != last:
            r["why"] = f"last note {last} is not the most held {held}"
            res[name] = r
            continue
        cmaj = sum(pcs[(last + x) % 12] for x in MAJOR) / total
        cmin = sum(pcs[(last + x) % 12] for x in (0, 2, 3, 5, 7, 8, 10, 11)) / total
        r["cover_major"], r["cover_minor"] = round(cmaj, 4), round(cmin, 4)
        if (cmaj >= 0.95) == (cmin >= 0.95):
            r["why"] = f"scale coverage major {cmaj:.3f} minor {cmin:.3f}"
        else:
            r["key"] = last + (0 if cmaj >= 0.95 else 12)
        res[name] = r
    json.dump(res, open(a.out, "w"), indent=1, ensure_ascii=False)
    lab = [v for v in res.values() if v["key"] >= 0]
    print(len(res), "clips;", len(lab), "labelled:", sum(v["key"] >= 12 for v in lab), "minor,", sum(v["key"] < 12 for v in lab), "major")
    sm = [v["signature_matches_notes"] for v in res.values() if "signature_matches_notes" in v]
    print("score <key> agrees with the notes' best major scale in", sum(sm), "of", len(sm), "clips")


if __name__ == "__main__":
    main()
