"""Live-use evaluation of S-KEY (Deezer, ICASSP 2025) against and together with KeyCatch's key finder.

At moments 10, 14, 18, ... s into each clip (every 4 s), as the app's screen would read it:
  app      KeyCatch's shown key (AnalysisResult.displayKey) from the Swift MusicAnalyzer (kckey driver)
  skeyW    S-KEY on the last W s of audio (W = 20, 30; all of it while less has been heard)
  rules    S-KEY as an extra vote on the app's reading (see RULES below)
Scores per reading: exact key, MIREX weighted score (1 / 0.5 fifth / 0.3 relative / 0.2 parallel),
and the major vs relative-minor error (the reading is the relative of the true key).

Steps (each caches its output under --work, so a killed run can be restarted):
  prepare   collect clips and key labels            -> work/items.json
  run       app (kckey) + S-KEY on every clip       -> work/res/<id>.json
  check     S-KEY sliced-from-the-clip vs per-window -> stdout
  score     print the tables                        -> stdout (markdown)

All Python here runs with `python -I`; the S-KEY code is imported from a checkout given by --skey-src.
"""
import argparse
import glob
import json
import os
import subprocess
import sys
import tempfile
import time
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from skey_common import PITCH, category, key_name, mirex, parse_name, relative  # noqa: E402

FIRST, EVERY = 10.0, 4.0
WINDOWS = (20, 30)
APP_RATE = 44100
SK_RATE = 22050
HOP = 512


# ----------------------------------------------------------------------------- prepare

def prepare(a):
    items = []
    if a.gtzan_audio:
        for g in sorted(os.listdir(a.gtzan_keys)):
            for f in sorted(os.listdir(os.path.join(a.gtzan_keys, g))):
                if not f.endswith(".lerch.txt"):
                    continue
                v = int(open(os.path.join(a.gtzan_keys, g, f), "rb").read().decode("utf-8", "replace").split()[0])
                if v < 0:
                    continue
                # Lerch's numbering: 0..11 = A, A#, ..., G# major; 12..23 = a .. g# minor
                key = (v + 9) % 12 + (12 if v >= 12 else 0)
                num = f.split(".")[1]
                path = os.path.join(a.gtzan_audio, g, f"{g}.{num}.wav")
                if not readable(path):
                    continue
                items.append(dict(id=f"gtzan_{g}.{num}", dataset="gtzan", group=g, audio=path, key=key,
                                  presets=["band"], detune=0))
    if a.guitarset_audio:
        for jf in sorted(glob.glob(os.path.join(a.guitarset_ann, "*.jams"))):
            stem = os.path.basename(jf)[:-5]
            kind = "solo" if stem.endswith("_solo") else "comp"
            j = json.load(open(jf))
            km = [x for x in j["annotations"] if x["namespace"] == "key_mode"][0]["data"]
            assert len({d["value"] for d in km}) == 1
            key = parse_name(km[0]["value"])
            style = stem.split("_")[1].split("-")[0].rstrip("0123456789")
            for det in (0, 39):
                items.append(dict(id=f"guitarset_{stem}" + (f"_d{det}" if det else ""),
                                  dataset=f"guitarset_{kind}" + (f"_d{det}" if det else ""), group=style,
                                  audio=os.path.join(a.guitarset_audio, stem + "_mic.wav"), key=key,
                                  presets=["guitar", "band"], detune=det, tuned="guitar"))
    if a.giantsteps_audio:
        for kf in sorted(glob.glob(os.path.join(a.giantsteps_ann, "*.key"))):
            b = os.path.basename(kf)[:-4]
            path = os.path.join(a.giantsteps_audio, b + ".mp3")
            if not os.path.exists(path):
                continue
            items.append(dict(id=f"giantsteps_{b}", dataset="giantsteps", group="edm", audio=path,
                              key=parse_name(open(kf).read()), presets=["band"], detune=0))
    if a.csd_audio:
        # Children's Song Dataset, English: one professional singer, vocals only; derived labels
        for name, v in sorted(json.load(open(a.csd_labels)).items()):
            path = os.path.join(a.csd_audio, name + ".flac")
            if v["key"] < 0 or not os.path.exists(path):
                continue
            items.append(dict(id=f"csd_{name}", dataset="csd", group="csd", audio=path, key=v["key"],
                              presets=["voices"], detune=0, tuned="voices"))
    if a.singing:
        for m in json.load(open(a.singing)):
            items.append(dict(id=f"singing_{m['name']}", dataset="singing", group=m["group"], audio=m["path"],
                              key=m["key"], presets=["voices"], detune=0, tuned="voices", final=True))
    os.makedirs(a.work, exist_ok=True)
    json.dump(items, open(os.path.join(a.work, "items.json"), "w"))
    print(Counter(i["dataset"] for i in items))


def readable(path):
    import soundfile as sf
    try:
        sf.info(path)
        return True
    except Exception:  # jazz.00054 in the common GTZAN copy has a corrupt header
        print("skipping unreadable", path)
        return False


def load_items(work):
    return json.load(open(os.path.join(work, "items.json")))


def load_audio(path):
    if path.endswith(".f32"):
        return np.fromfile(path, dtype="<f4").astype(np.float64), 44100
    import soundfile as sf
    x, r = sf.read(path, dtype="float64", always_2d=True)
    return x.mean(1), r


def resample(x, in_rate, out_rate):
    import soxr
    if abs(in_rate - out_rate) < 1e-9:
        return x
    return soxr.resample(x, in_rate, out_rate)


def moments_for(duration, final=False):
    m = []
    t = FIRST
    while t <= duration - 0.05:
        m.append(t)
        t += EVERY
    if final:  # the short sung test tunes: also their last whole second, as the tests read them
        last = float(int(duration - 0.05))
        if last >= 4 and last not in m:
            m.append(last)
    return m


# ----------------------------------------------------------------------------- S-KEY, live

class LiveSKey:
    """S-KEY on windows cut from one clip.

    The official code computes the VQT of each window from its own audio. Here the VQT magnitude
    of the whole clip is computed once and the window's frames are sliced from it (frame j is
    centred on sample 512 j), then scaled, converted to dB and clipped exactly as the official
    VQT.forward does for that window: peak normalisation of the window's audio (a gain, so a
    shift in dB), 20 log10, clipped at the window's maximum - 80 dB, /80 + 1. Only the frames
    within a kernel half-length of the window's edges differ (the official per-window VQT
    reflect-pads there); `check` measures the difference."""

    def __init__(self, src, threads=1):
        from skey_common import SKey
        import torch
        self.m = SKey(src, threads=threads)
        self.torch = torch
        import nnAudio.features.vqt as nv
        self._vqt_forward = nv.VQT.forward

    def magnitude(self, x22):
        torch = self.torch
        with torch.no_grad():
            t = torch.from_numpy(np.asarray(x22, dtype=np.float32))[None, None, :]
            mag = self._vqt_forward(self.m.hcqt, t, "Magnitude", "librosa")  # (1, 99, T)
        return mag[0].numpy()

    def window_probs(self, mag, x22, s0, s1):
        """Probabilities (our key order) for the window of samples [s0, s1); s0 a multiple of HOP."""
        torch = self.torch
        j0 = s0 // HOP
        n = 1 + (s1 - s0) // HOP
        seg = mag[:, j0:j0 + n]
        peak = np.abs(x22[s0:s1]).max()
        if peak <= 0:
            return None
        db = 20.0 * np.log10(np.maximum(seg / peak, 1e-10))
        db = np.maximum(db, db.max() - 80.0)
        feat = (db / 80.0 + 1.0)[:84].astype(np.float32)
        with torch.no_grad():
            p = self.m.net(torch.from_numpy(feat)[None, None]).mean(0).numpy()
        out = np.zeros(24)
        out[self.m.order] = p
        return out

    def official(self, x22):
        return self.m.probs(x22)


def window_bounds(t, W, n_samples, factor=1.0):
    """Samples [s0, s1) of the last W s before moment t (t in the clip's own time; factor is the
    time scale of a pitch-corrected copy). s0 is aligned to the hop so frames line up."""
    s1 = min(n_samples, int(round(t / factor * SK_RATE)))
    s0 = max(0, int(round((t - W) / factor * SK_RATE / HOP)) * HOP)
    return s0, s1


# ----------------------------------------------------------------------------- run

_live = None


def _init(src):
    global _live
    _live = LiveSKey(src, threads=1)


def run_item(args):
    it, work, kckey = args
    out = os.path.join(work, "res", it["id"] + ".json")
    if os.path.exists(out):
        return it["id"], 0.0
    started = time.time()
    x, sr = load_audio(it["audio"])
    ratio = 2 ** (it["detune"] / 1200.0)
    # Detuning: the clip played faster by `ratio`, so every pitch is `detune` cents higher.
    x44 = resample(x, sr * ratio, APP_RATE)
    duration = len(x44) / APP_RATE
    moments = moments_for(duration, it.get("final", False))
    # --- the app
    readings = {}
    with tempfile.TemporaryDirectory(dir=os.path.join(work, "tmp")) as td:
        raw = os.path.join(td, "a.f32")
        x44.astype("<f4").tofile(raw)
        lst = os.path.join(td, "l.txt")
        with open(lst, "w") as f:
            for p in it["presets"]:
                f.write(f"{raw} {os.path.join(td, p + '.jsonl')} {APP_RATE} {p}\n")
        subprocess.run([kckey, lst], check=True)
        for p in it["presets"]:
            rows = {}
            cpu = None
            for line in open(os.path.join(td, p + ".jsonl")):
                r = json.loads(line)
                if "moment" in r:
                    rows[r["moment"]] = r
                elif "cpuMs" in r:
                    cpu = r["cpuMs"]
            readings[p] = dict(cpuMs=cpu, at={str(m): rows.get(m) for m in moments})
    # --- S-KEY
    x22 = resample(x, sr * ratio, SK_RATE)
    mag = _live.magnitude(x22)
    sk = {}
    for W in WINDOWS:
        sk[str(W)] = {}
        for m in moments:
            s0, s1 = window_bounds(m, W, len(x22))
            p = _live.window_probs(mag, x22, s0, s1)
            sk[str(W)][str(m)] = None if p is None else [round(float(v), 6) for v in p]
    p = _live.window_probs(mag, x22, 0, len(x22))
    sk["full"] = None if p is None else [round(float(v), 6) for v in p]
    # --- S-KEY on audio moved onto concert pitch by the app's own tuning reading at that moment
    if it.get("tuned"):
        cache = {}
        for W in WINDOWS:
            sk[f"tuned{W}"] = {}
        for m in moments:
            r = readings[it["tuned"]]["at"][str(m)]
            c = 0.0 if r is None else float(r.get("tuning", 0.0))
            q = int(round(c / 5.0)) * 5
            f = ratio * 2 ** (-q / 1200.0)
            if q not in cache:
                xq = resample(x, sr * f, SK_RATE)
                cache[q] = (xq, _live.magnitude(xq))
            xq, mq = cache[q]
            for W in WINDOWS:
                s0, s1 = window_bounds(m, W, len(xq), factor=f / ratio)
                p = _live.window_probs(mq, xq, s0, s1)
                sk[f"tuned{W}"][str(m)] = None if p is None else [round(float(v), 6) for v in p]
    res = dict(id=it["id"], duration=duration, moments=moments, app=readings, skey=sk, seconds=time.time() - started)
    tmp = out + ".tmp"
    json.dump(res, open(tmp, "w"))
    os.replace(tmp, out)
    return it["id"], time.time() - started


def run(a):
    from multiprocessing import Pool
    items = load_items(a.work)
    if a.datasets:
        items = [i for i in items if i["dataset"] in a.datasets]
    os.makedirs(os.path.join(a.work, "res"), exist_ok=True)
    os.makedirs(os.path.join(a.work, "tmp"), exist_ok=True)
    todo = [i for i in items if not os.path.exists(os.path.join(a.work, "res", i["id"] + ".json"))]
    print(len(items), "items,", len(todo), "to do", flush=True)
    done = 0
    t0 = time.time()
    with Pool(a.jobs, initializer=_init, initargs=(a.skey_src,)) as pool:
        for iid, sec in pool.imap_unordered(run_item, [(i, a.work, a.kckey) for i in todo]):
            done += 1
            if done % 25 == 0 or done == len(todo):
                print(f"{done}/{len(todo)} {iid} {sec:.1f}s elapsed {time.time() - t0:.0f}s", flush=True)


# ----------------------------------------------------------------------------- kt (JVM, default models)

def kt_presets(it):
    """The JVM run uses the app's default models; Band and Guitar both hear through Basic Pitch,
    so only one of them is run (Guitar for GuitarSet, Band elsewhere)."""
    return ["guitar"] if "guitar" in it["presets"] else it["presets"][:1]


def ktrun(a):
    """Runs the Kotlin MusicAnalyzer with Basic Pitch (Band, Guitar) and CREPE (Voices), the app's
    defaults, through KeyReadingsDump in a scratch copy of the repo, in batches (audio is written
    as float32 at 44.1 kHz for a batch, then deleted)."""
    import random
    items = load_items(a.work)
    if a.datasets:
        items = [i for i in items if i["dataset"] in a.datasets]
    if a.sample_per_group:
        rng = random.Random(0)
        by = defaultdict(list)
        for i in items:
            by[(i["dataset"], i["group"])].append(i)
        keep = set()
        for k, lst in by.items():
            for i in (lst if len(lst) <= a.sample_per_group else rng.sample(lst, a.sample_per_group)):
                keep.add(i["id"])
        items = [i for i in items if i["id"] in keep]
    work = os.path.abspath(a.work)  # gradle runs in the repo copy: every path must be absolute
    out_dir = os.path.join(work, "kt")
    todo = []
    for it in items:
        for p in kt_presets(it):
            os.makedirs(os.path.join(out_dir, p), exist_ok=True)
            if not os.path.exists(os.path.join(out_dir, p, it["id"] + ".jsonl")):
                todo.append((it, p))
    print(len(items), "items,", len(todo), "runs to do", flush=True)
    tmp = os.path.join(work, "kt_tmp")
    os.makedirs(tmp, exist_ok=True)
    for b in range(0, len(todo), a.batch):
        batch = todo[b:b + a.batch]
        lines = []
        for it, p in batch:
            raw = os.path.join(tmp, it["id"] + ".f32")
            if not os.path.exists(raw):
                x, sr = load_audio(it["audio"])
                resample(x, sr * 2 ** (it["detune"] / 1200.0), APP_RATE).astype("<f4").tofile(raw)
            lines.append(f"{raw} {os.path.join(out_dir, p, it['id'] + '.jsonl')} {APP_RATE} {p}")
        lst = os.path.join(tmp, "list.txt")
        open(lst, "w").write("\n".join(lines) + "\n")
        t0 = time.time()
        subprocess.run(["./gradlew", "--offline", "-q", ":analysis:testDebugUnitTest", "--tests", "*KeyReadingsDump*", "--rerun",
                        f"-Pkc.list={lst}", f"-Pkc.threads={a.jobs}"], cwd=a.repo, check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for it, p in batch:
            raw = os.path.join(tmp, it["id"] + ".f32")
            if os.path.exists(raw):
                os.remove(raw)
        print(f"batch {b // a.batch + 1}: {len(batch)} runs in {time.time() - t0:.0f} s", flush=True)


# ----------------------------------------------------------------------------- check

def check(a):
    """Sliced-from-the-clip S-KEY vs the official per-window computation, on a sample of windows."""
    import random
    _init(a.skey_src)
    items = load_items(a.work)
    rng = random.Random(1)
    sample = rng.sample(items, min(a.n, len(items)))
    rows = []
    for it in sample:
        x, sr = load_audio(it["audio"])
        ratio = 2 ** (it["detune"] / 1200.0)
        x22 = resample(x, sr * ratio, SK_RATE)
        mag = _live.magnitude(x22)
        ms = moments_for(len(x22) / SK_RATE, it.get("final", False))
        if not ms:
            continue
        m = rng.choice(ms)
        for W in WINDOWS:
            s0, s1 = window_bounds(m, W, len(x22))
            ps = _live.window_probs(mag, x22, s0, s1)
            po = _live.official(x22[s0:s1])
            # the same window ending 0.75 s earlier: no audio after the moment is used at all
            d0, d1 = window_bounds(m - 0.75, W, len(x22))
            pd = _live.window_probs(mag, x22, d0, d1)
            rows.append((it["dataset"], W, int(ps.argmax()), int(po.argmax()), float(np.abs(ps - po).max()),
                         int(pd.argmax()), int(it["key"])))
    n = len(rows)
    same = sum(r[2] == r[3] for r in rows)
    same_d = sum(r[5] == r[3] for r in rows)
    diff = np.array([r[4] for r in rows])
    print(f"windows {n}: argmax sliced == official {same} ({100 * same / n:.1f} %), max |p diff| median {np.median(diff):.4f} "
          f"p95 {np.percentile(diff, 95):.4f} max {diff.max():.4f}")
    print(f"window ending 0.75 s earlier (no look-ahead) == official at the moment: {same_d} ({100 * same_d / n:.1f} %)")
    print(f"correct: sliced {sum(r[2] == r[6] for r in rows)}, official {sum(r[3] == r[6] for r in rows)}, "
          f"0.75 s earlier {sum(r[5] == r[6] for r in rows)} of {n}")


# ----------------------------------------------------------------------------- score

def pair_of(k):
    """(major, relative minor) of k's pair."""
    major = k if k < 12 else (k - 12 + 3) % 12
    return major, (major + 9) % 12 + 12


def app_score(r):
    """The app's own major(+)/minor(-) evidence for the pair it shows. modeScore belongs to the
    pair of `key` (the note/chroma finder); when the chord estimate overrides with another pair,
    the app treats that as decided: use +-threshold."""
    d = r["display"]
    if d < 0:
        return 0.0
    if r["fromChords"]:  # displayKeyFromChords is set only when the chords point at another pair
        return 1.5 if d < 12 else -1.5
    return float(r["modeScore"])


RULES = {
    # S-KEY decides only which of the app's pair (major or its relative minor) is shown.
    "pair": "S-KEY picks major vs relative minor within the app's pair",
    # The same, but only while the app itself has not decided the mode (modeDecided false).
    "pair-undecided": "as pair, only while the app's mode is undecided",
    # The app's mode evidence plus S-KEY's log-odds for the pair, weight w (a priori w = 1).
    "sum": "app modeScore + 1.0 * ln(p_major / p_minor) of S-KEY, sign picks the mode",
    # S-KEY replaces the app's key when it is confident about a different pair, else rule pair.
    "override": "S-KEY's key if p_top >= 0.5 and another pair, else rule pair",
}


def combine(rule, r, p, w=1.0):
    d = r["display"]
    if d < 0 or p is None:
        return d
    M, m = pair_of(d)
    if rule == "pair":
        return M if p[M] >= p[m] else m
    if rule == "pair-undecided":
        if r["modeDecided"]:
            return d
        return M if p[M] >= p[m] else m
    if rule == "sum":
        s = app_score(r) + w * np.log(max(p[M], 1e-9) / max(p[m], 1e-9))
        return M if s > 0 else (m if s < 0 else d)
    if rule == "override":
        top = int(np.argmax(p))
        if p[top] >= 0.5 and pair_of(top) != (M, m):
            return top
        return M if p[M] >= p[m] else m
    raise ValueError(rule)


def collect(work, app="swift"):
    """(item, result) pairs. app="kt": the app's readings come from the JVM run with the default
    models (work/kt/<preset>/<id>.jsonl) instead of the Swift kckey run; items without one are left out."""
    items = {i["id"]: i for i in load_items(work)}
    out = []
    for f in sorted(glob.glob(os.path.join(work, "res", "*.json"))):
        r = json.load(open(f))
        it = items.get(r["id"])
        if it is None:
            continue
        if app == "kt":
            presets = kt_presets(it)
            got = {}
            for p in presets:
                fn = os.path.join(work, "kt", p, it["id"] + ".jsonl")
                if not os.path.exists(fn):
                    break
                rows = {}
                for line in open(fn):
                    d = json.loads(line)
                    if "moment" in d:
                        rows[d["moment"]] = d
                got[p] = dict(at={str(m): rows.get(m) for m in r["moments"]})
            if len(got) != len(presets):
                continue
            it = dict(it, presets=presets)
            r = dict(r, app=got)
        out.append((it, r))
    return out


class Tally:
    def __init__(self):
        self.n = 0
        self.c = Counter()
        self.mx = 0.0

    def add(self, est, ref):
        self.n += 1
        self.c[category(est, ref)] += 1
        self.mx += mirex(est, ref)

    def row(self):
        n = max(self.n, 1)
        ex, rel = self.c["exact"], self.c["relative"]
        return [100 * ex / n, 100 * self.mx / n, 100 * rel / n, 100 * rel / max(ex + rel, 1), 100 * self.c["none"] / n]


def systems_for(skey_keys):
    s = ["app"]
    for W in WINDOWS:
        s.append(f"skey{W}")
    for W in WINDOWS:
        if f"tuned{W}" in skey_keys:
            s.append(f"skey{W}-tuned")
    for rule in RULES:
        for W in WINDOWS:
            s.append(f"{rule}/{W}")
        # the same rules on S-KEY fed audio moved onto concert pitch by the app's tuning reading
        for W in WINDOWS:
            if f"tuned{W}" in skey_keys:
                s.append(f"{rule}/{W}t")
    return s


def estimate(system, r, sk, mom):
    if system == "app":
        return r["display"]
    if system.startswith("skey"):
        W = system[4:6]
        key = f"tuned{W}" if system.endswith("-tuned") else W
        p = sk.get(key, {}).get(mom)
        return -1 if p is None else int(np.argmax(p))
    rule, W = system.split("/")
    p = sk[f"tuned{W[:-1]}"].get(mom) if W.endswith("t") else sk[W].get(mom)
    return combine(rule, r, None if p is None else np.asarray(p))


def score(a):
    data = collect(a.work, a.app)
    by = defaultdict(lambda: defaultdict(Tally))
    sweep = defaultdict(lambda: defaultdict(Tally))
    decided = defaultdict(lambda: defaultdict(Tally))
    group_t = defaultdict(lambda: defaultdict(Tally))
    offline = defaultdict(Tally)
    first_moment = defaultdict(lambda: defaultdict(Tally))
    for it, r in data:
        ds = it["dataset"]
        sk = r["skey"]
        if sk.get("full") is not None:
            offline[ds].add(int(np.argmax(sk["full"])), it["key"])
        for preset in it["presets"]:
            rows = r["app"][preset]["at"]
            for mom in [str(m) for m in r["moments"]]:
                rd = rows.get(mom)
                if rd is None:
                    continue
                for sysname in systems_for(sk.keys()):
                    est = estimate(sysname, rd, sk, mom)
                    by[(ds, preset)][sysname].add(est, it["key"])
                    group_t[(ds, preset, it["group"])][sysname].add(est, it["key"])
                    if sysname in ("app", "skey20", "skey30") and rd["display"] >= 0:
                        decided[(ds, preset, "decided" if rd["modeDecided"] else "undecided")][sysname].add(est, it["key"])
                if float(mom) == FIRST:
                    for sysname in ("app", "skey20", "pair/20"):
                        first_moment[(ds, preset)][sysname].add(estimate(sysname, rd, sk, mom), it["key"])
                p = sk["20"].get(mom)
                for w in (0.25, 0.5, 1.0, 2.0, 4.0):
                    sweep[(ds, preset)][w].add(combine("sum", rd, None if p is None else np.asarray(p), w), it["key"])
    order = ["singing", "csd", "guitarset_comp", "guitarset_solo", "guitarset_comp_d39", "guitarset_solo_d39", "gtzan", "giantsteps"]
    keys = sorted(by.keys(), key=lambda k: (order.index(k[0]) if k[0] in order else 99, k[1]))
    if a.brief:
        cols = ["app", "skey20", "skey30", "skey20-tuned", "pair/20", "pair-undecided/20", "sum/20", "override/20",
                "pair-undecided/20t", "sum/20t", "override/20t"]
        for metric, idx in (("exact %", 0), ("MIREX %", 1), ("relative % (reading is the true key's relative)", 2)):
            print(f"\n### {metric}\n")
            print("| dataset, preset | clips | readings | " + " | ".join(cols) + " |")
            print("|---" * (len(cols) + 3) + "|")
            for k in keys:
                n_clips = len({it["id"] for it, _ in data if it["dataset"] == k[0]})
                cells = [f"{by[k][c].row()[idx]:.1f}" if c in by[k] else "" for c in cols]
                print(f"| {k[0]}, {k[1]} | {n_clips} | {by[k]['app'].n} | " + " | ".join(cells) + " |")
        return
    print("## Readings: exact %, MIREX %, relative % (reading = relative of the truth), relative share of in-pair readings %, none %\n")
    for k in keys:
        n_clips = len({it["id"] for it, _ in data if it["dataset"] == k[0]})
        print(f"### {k[0]}, preset {k[1]} ({n_clips} clips, {by[k]['app'].n} readings)\n")
        print("| system | exact | MIREX | relative | rel. share in pair | none |")
        print("|---|---|---|---|---|---|")
        for s, t in by[k].items():
            v = t.row()
            print(f"| {s} | {v[0]:.1f} | {v[1]:.1f} | {v[2]:.1f} | {v[3]:.1f} | {v[4]:.1f} |")
        print()
    print("## Offline, whole clip, official-style (one reading per clip): exact %, MIREX %, relative %\n")
    print("| dataset | clips | exact | MIREX | relative |")
    print("|---|---|---|---|---|")
    for ds in order:
        if ds in offline:
            v = offline[ds].row()
            print(f"| {ds} | {offline[ds].n} | {v[0]:.1f} | {v[1]:.1f} | {v[2]:.1f} |")
    print("\n## Split by the app's own mode decision (readings with a key): exact % / relative %\n")
    print("| dataset, preset | app decided: n | app | skey20 | skey30 | app undecided: n | app | skey20 | skey30 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for k in keys:
        d = decided[(k[0], k[1], "decided")]
        u = decided[(k[0], k[1], "undecided")]

        def f(t, s):
            v = t[s].row() if s in t else [0] * 5
            return f"{v[0]:.1f} / {v[2]:.1f}"
        print(f"| {k[0]}, {k[1]} | {d['app'].n} | {f(d, 'app')} | {f(d, 'skey20')} | {f(d, 'skey30')} | "
              f"{u['app'].n} | {f(u, 'app')} | {f(u, 'skey20')} | {f(u, 'skey30')} |")
    print("\n## Rule `sum` (W = 20): exact % for weights w of S-KEY's log-odds\n")
    ws = (0.25, 0.5, 1.0, 2.0, 4.0)
    print("| dataset, preset | " + " | ".join(f"w={w}" for w in ws) + " |")
    print("|---" * (len(ws) + 1) + "|")
    for k in keys:
        print(f"| {k[0]}, {k[1]} | " + " | ".join(f"{sweep[k][w].row()[0]:.1f}" for w in ws) + " |")
    print("\n## First reading (10 s): exact % / relative %\n")
    print("| dataset, preset | n | app | skey20 | pair/20 |")
    print("|---|---|---|---|---|")
    for k in keys:
        t = first_moment[k]
        print(f"| {k[0]}, {k[1]} | {t['app'].n} | " + " | ".join(f"{t[s].row()[0]:.1f} / {t[s].row()[2]:.1f}" for s in ("app", "skey20", "pair/20")) + " |")
    if a.groups:
        print("\n## Per group: exact % (app / skey20 / pair/20 / sum/20)\n")
        print("| dataset, preset, group | readings | app | skey20 | pair/20 | sum/20 |")
        print("|---|---|---|---|---|---|")
        for g in sorted(group_t.keys(), key=lambda k: (order.index(k[0]) if k[0] in order else 99, k[1], k[2])):
            t = group_t[g]
            print(f"| {g[0]}, {g[1]}, {g[2]} | {t['app'].n} | " + " | ".join(f"{t[s].row()[0]:.1f}" for s in ("app", "skey20", "pair/20", "sum/20")) + " |")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--work", required=True)
    sub = p.add_subparsers(dest="cmd", required=True)
    q = sub.add_parser("prepare")
    q.add_argument("--gtzan-audio")
    q.add_argument("--gtzan-keys")
    q.add_argument("--guitarset-audio")
    q.add_argument("--guitarset-ann")
    q.add_argument("--giantsteps-audio")
    q.add_argument("--giantsteps-ann")
    q.add_argument("--singing")
    q.add_argument("--csd-audio")
    q.add_argument("--csd-labels")
    q = sub.add_parser("run")
    q.add_argument("--kckey", required=True)
    q.add_argument("--skey-src", required=True)
    q.add_argument("--jobs", type=int, default=3)
    q.add_argument("--datasets", nargs="*")
    q = sub.add_parser("ktrun")
    q.add_argument("--repo", required=True, help="scratch copy of the repo with KeyReadingsDump.kt added")
    q.add_argument("--jobs", type=int, default=3)
    q.add_argument("--batch", type=int, default=60)
    q.add_argument("--datasets", nargs="*")
    q.add_argument("--sample-per-group", type=int, default=0)
    q = sub.add_parser("check")
    q.add_argument("--skey-src", required=True)
    q.add_argument("--n", type=int, default=150)
    q = sub.add_parser("score")
    q.add_argument("--groups", action="store_true")
    q.add_argument("--brief", action="store_true", help="one table per metric, main systems only")
    q.add_argument("--app", choices=["swift", "kt"], default="swift",
                   help="swift: KeyCatchCore without Core ML models (spectral chroma, autocorrelation pitch); "
                        "kt: Kotlin engine with Basic Pitch / CREPE (the apps' defaults)")
    a = p.parse_args()
    {"prepare": prepare, "run": run, "ktrun": ktrun, "check": check, "score": score}[a.cmd](a)


if __name__ == "__main__":
    main()
