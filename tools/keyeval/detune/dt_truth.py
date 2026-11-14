"""The notes each RS200 sung tune was rendered from (rs200_synth.py, same order, seeds and scatter):
per clip a list of [start s, length s, midi, scatter cents]. Used by dt_analyse.py to say which
of the app's notes landed on the right pitch class.

usage: python -I dt_truth.py rs200_melody_nlt.zip rs200_harmony_clt.zip OUT.json
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
for p in (os.path.join(HERE, ".."), os.path.join(HERE, "..", "..", "skey")):
    sys.path.insert(0, os.path.abspath(p))
import rs200_synth as rs  # noqa: E402
from singing_synth import JavaRandom  # noqa: E402


def main():
    mels = rs.read_zip(sys.argv[1], ".nlt")
    harms = rs.read_zip(sys.argv[2], ".clt")
    labelled = []
    for name in sorted(mels):
        mel = [r for r in mels[name] if len(r) >= 4]
        harm = harms.get(name)
        if not mel or harm is None:
            continue
        key, _ = rs.label(mel, harm)
        if key is None:
            continue
        notes = rs.notes_of(mel)
        if notes[-1][0] + notes[-1][1] < 25.0:
            continue
        labelled.append((name, key, notes))
    majors = [x for x in labelled if x[1] < 12][:26]
    keep = [x for x in labelled if x[1] >= 12] + majors
    out = {}
    for si, (name, key, notes) in enumerate(sorted(keep)):
        for singer, err in (("good", 15.0), ("average", 30.0)):
            rnd = JavaRandom(1000 + si)
            out[f"rs200_{name}_{singer}"] = dict(key=key, notes=[[t0, ln, m, rnd.next_gaussian() * err] for t0, ln, m in notes])
    json.dump(out, open(sys.argv[3], "w"))
    print(len(out), "clips")


if __name__ == "__main__":
    main()
