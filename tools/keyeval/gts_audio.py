"""Fetches the vocal WAVs of the GTSinger clips gts_select.py labelled and joins each clip's
segments in order with 0.4 s of silence into one mono FLAC (the segment WAVs are deleted).

usage: python -I gts_audio.py LABELS.json WAV_DIR OUT_DIR   -> OUT_DIR/<clip>.flac + OUT_DIR/gtsinger.json
"""
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import soundfile as sf  # noqa: E402

from gts_select import fetch  # noqa: E402


def main():
    labels, wav_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    meta = []
    for name, v in sorted(json.load(open(labels)).items()):
        if v["key"] < 0:
            continue
        clip = "gts_" + re.sub(r"[^A-Za-z0-9]+", "_", f"{v['lang'][:2]}_{v['singer']}_{v['song']}").strip("_")
        if not re.search(r"[A-Za-z]{3}", v["song"]):
            clip += "_" + hashlib.md5(v["song"].encode()).hexdigest()[:6]
        dest = os.path.join(out, clip + ".flac")
        if not os.path.exists(dest):
            parts, rate = [], None
            for rel in v["wavs"]:
                w = os.path.join(wav_dir, rel)
                if not fetch(rel, w):
                    raise SystemExit(f"could not fetch {rel}")
                x, r = sf.read(w, dtype="float64", always_2d=True)
                rate = rate or r
                assert r == rate
                parts += [x.mean(1), np.zeros(int(0.4 * r))]
                os.remove(w)
            sf.write(dest + ".part.flac", np.concatenate(parts[:-1]), rate, format="FLAC", subtype="PCM_24")
            os.replace(dest + ".part.flac", dest)
        info = sf.info(dest)
        meta.append(dict(name=clip, source=name, key=v["key"], path=dest, seconds=info.frames / info.samplerate,
                         song=v["song"], singer=v["singer"], lang=v["lang"]))
        print(clip, v["key"], round(meta[-1]["seconds"], 1), flush=True)
    json.dump(meta, open(os.path.join(out, "gtsinger.json"), "w"), indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
