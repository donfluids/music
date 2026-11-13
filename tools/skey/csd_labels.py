"""Key labels for the English Children's Song Dataset, derived from its note annotations (the
dataset has no key labels and its MIDI files carry no key signatures).

Rule: the key signature is the major scale (seven pitch classes) that covers most of the sung
time; the song ends at home, so the last sung note names the key: on the scale's first degree
(do) it is that major key, on its sixth (la) its relative minor. A song whose best scale covers
less than 97 % of the sung time or that ends on any other degree is left out ("unlabelled").
Tunes of five or six notes fit several scales fully; then the scale on whose do or la the tune
ends is taken, and if that is still not one key (do of one scale, la of another) it is left out. Checked: each song's two recordings ('a' and 'b') must come out
exactly `key_diff` semitones apart (metadata.json) with the same mode.
These are derived labels, not annotations: treat them as such.

usage: python -I csd_labels.py CSD_ENGLISH_DIR OUT.json
"""
import csv
import glob
import json
import os
import sys
from collections import defaultdict


MAJOR = (0, 2, 4, 5, 7, 9, 11)


def label(path):
    notes = [(float(r["start"]), float(r["end"]), int(r["pitch"])) for r in csv.DictReader(open(path))]
    pcs = defaultdict(float)
    for s, e, p in notes:
        pcs[p % 12] += e - s
    total = sum(pcs.values())
    cover = {t: sum(pcs[(t + d) % 12] for d in MAJOR) / total for t in range(12)}
    c1 = max(cover.values())
    best = [t for t in range(12) if cover[t] >= c1 - 1e-9]
    last = notes[-1][2] % 12
    if c1 < 0.97:
        return -1, c1, f"best scale covers {c1:.3f}", notes[-1][1]
    keys = {do for do in best if last == do} | {(do + 9) % 12 + 12 for do in best if last == (do + 9) % 12}
    if len(keys) == 1:
        return keys.pop(), c1, None, notes[-1][1]
    if not keys:
        return -1, c1, f"ends on degree {(last - best[0]) % 12} of the best scale" + (" (scales tie)" if len(best) > 1 else ""), notes[-1][1]
    return -1, c1, f"ambiguous: {sorted(keys)}", notes[-1][1]


def main():
    d, out = sys.argv[1], sys.argv[2]
    meta = json.load(open(os.path.join(d, "metadata.json")))
    labels = {}
    for f in sorted(glob.glob(os.path.join(d, "csv", "*.csv"))):
        name = os.path.basename(f)[:-4]
        k, cover, why, end = label(f)
        labels[name] = dict(key=k, cover=round(cover, 4), unlabelled=why,
                            song=meta[name]["songname"], key_diff=meta[name]["key_diff"], last_note_end=end)
    bad = []
    for name, v in labels.items():
        if name.endswith("a"):
            b = labels.get(name[:-1] + "b")
            if b is None:
                continue
            ka, kb = v["key"], b["key"]
            if ka < 0 or kb < 0:
                if (ka < 0) != (kb < 0):
                    bad.append((name, ka, kb, "one unlabelled"))
                continue
            diff = b["key_diff"] - v["key_diff"]
            if (ka >= 12) != (kb >= 12) or (ka - kb) % 12 != (-diff) % 12 and (kb - ka) % 12 != diff % 12:
                bad.append((name, ka, kb, diff))
    for name, *_ in bad:  # a song whose two recordings disagree is left out entirely
        for v in (labels[name], labels[name[:-1] + "b"]):
            v["key"], v["unlabelled"] = -1, v["unlabelled"] or "its other recording disagrees"
    print(len(labels), "recordings;", sum(v["key"] >= 0 for v in labels.values()), "labelled,",
          sum(v["key"] >= 12 for v in labels.values()), "minor; a/b mismatches:", bad)
    for n, v in labels.items():
        if v["key"] < 0 and n.endswith("a"):
            print("  unlabelled", n, v["song"], v["unlabelled"])
    json.dump(labels, open(out, "w"), indent=1)


if __name__ == "__main__":
    main()
