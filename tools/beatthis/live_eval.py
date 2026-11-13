"""Live-use evaluation of KeyCatch's beat tracker (Swift MusicAnalyzer) against Beat This!.

Every second from 4 s into a clip, each system is asked for (bpm, beats per bar, time of the
most recent beat), as the app's screen would read it. Scores per moment:
  tempo_strict  bpm within 4 % of the annotated local tempo
  tempo_octave  same, also accepting x2 and /2
  bpb           beats per bar equal to the annotated bar length
  phase         next beat, extrapolated from the last beat and the period, within 70 ms of an annotated beat
plus the time to the first strictly correct tempo and the offline beat F-measure (mir_eval,
first 5 s trimmed) for reference.

Steps (each caches its output under --work, so they can be rerun or extended):
  prepare   collect clips and annotations                     -> work/items.json
  swift     run the Swift driver (kclive) on every clip       -> work/swift/<preset>/<id>.jsonl
  beatthis  run Beat This! live and offline on every clip     -> work/bt/<ckpt>/<id>.json
  score     print the tables                                  -> stdout (markdown)

All Python here runs with `python -I`; downloaded data is only read, never imported.
See README.md for the exact commands used.
"""
import argparse
import csv
import glob
import json
import os
import random
import subprocess
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

FPS = 50
BORDER = 6  # zero frames the official inference puts on each side of a short excerpt
FIRST_MOMENT = 4.0
TEMPO_TOL = 0.04
PHASE_TOL = 0.07

# Which checkpoints saw which data in training (from the checkpoints' datamodule
# hyperparameters and the split files of github.com/CPJKU/beat_this_annotations).
#  final*/small*: every dataset except GTZAN, train+val of the single split (no_val=True).
#  single_*: only the "train" part of each dataset's single.split.
#  GuitarSet: only the 180 "comp" recordings are in Beat This!'s data; "solo" is unseen by all.


# ----------------------------------------------------------------------------- prepare

def read_beats_file(path):
    times, nums = [], []
    for line in open(path):
        parts = line.split()
        if not parts:
            continue
        times.append(float(parts[0]))
        nums.append(int(float(parts[1])) if len(parts) > 1 else 0)
    return times, nums


def readable(path):
    import soundfile as sf
    try:
        sf.info(path)
        return True
    except Exception:  # jazz.00054 in the common GTZAN copy has a corrupt header
        print("skipping unreadable", path)
        return False


def prepare(a):
    items = []
    rng = random.Random(0)
    # GTZAN: stratified random sample per genre, plus every 3/4, 12/8 and 9/8 clip.
    stats = {}
    if a.gtzan_rhythm:
        for r in csv.DictReader(open(a.gtzan_rhythm)):
            stats[r["filename"].replace(".wav", "")] = r
    if a.gtzan_audio:
        by_genre = defaultdict(list)
        for path in sorted(glob.glob(os.path.join(a.gtzan_audio, "*", "*.wav"))):
            base = os.path.basename(path)
            if base.startswith("._"):
                continue
            genre, num = base[:-4].split(".")
            ann = os.path.join(a.gtzan_ann, f"gtzan_{genre}_{num}.beats")
            if not os.path.exists(ann) or not readable(path):
                continue
            by_genre[genre].append((path, ann, f"{genre}.{num}"))
        chosen = set()
        for genre, lst in sorted(by_genre.items()):
            for x in rng.sample(lst, min(a.gtzan_per_genre, len(lst))):
                chosen.add(x[2])
        for genre, lst in sorted(by_genre.items()):
            for path, ann, key in lst:
                st = stats.get(key, {})
                meter = st.get("meter", "")
                odd = meter in ("3/4", "12/8", "9/8")
                if key not in chosen and not odd:
                    continue
                times, nums = read_beats_file(ann)
                items.append(dict(
                    id=f"gtzan_{key}", dataset="gtzan", group=genre, audio=path,
                    beats=times, nums=nums, random_sample=key in chosen,
                    meter=meter, ternary=st.get("ternary ?") == "yes", swing=st.get("swing ?") == "yes",
                    trained=[]))
    # Ballroom: the val part of the single split (unseen by single_* checkpoints).
    if a.ballroom_audio:
        split = {}
        for line in open(a.ballroom_split):
            k, v = line.rstrip("\n").split("\t")
            split[k] = v
        for style in sorted(os.listdir(a.ballroom_audio)):
            d = os.path.join(a.ballroom_audio, style)
            if not os.path.isdir(d) or style in ("nada", "allBallroomFiles"):
                continue
            for f in sorted(os.listdir(d)):
                if not f.endswith(".wav"):
                    continue
                key = "ballroom_" + f[:-4]
                if split.get(key) != "val":
                    continue
                times, nums = read_beats_file(os.path.join(a.ballroom_ann, key + ".beats"))
                items.append(dict(
                    id=key, dataset="ballroom", group=style, audio=os.path.join(d, f),
                    beats=times, nums=nums, meter="3/4" if "Waltz" in style else "",
                    trained=["small0", "final0"]))
    # GuitarSet: mono mic audio, beats from the JAMS beat_position annotations.
    if a.guitarset_audio:
        split = {}
        for line in open(a.guitarset_split):
            k, v = line.rstrip("\n").split("\t")
            split[k.replace("_comp_mix", "")] = v
        solo_seen = 0
        for jf in sorted(glob.glob(os.path.join(a.guitarset_ann, "*.jams"))):
            stem = os.path.basename(jf)[:-5]  # e.g. 00_BN1-129-Eb_comp
            kind = "solo" if stem.endswith("_solo") else "comp"
            if kind == "solo":
                solo_seen += 1
                if (solo_seen - 1) % a.guitarset_solo_every:
                    continue
            base = stem[: -len("_" + kind)]
            if kind == "comp" and split.get(base) != "val":
                continue  # comp tracks of the train part were seen by every checkpoint
            j = json.load(open(jf))
            bp = [x for x in j["annotations"] if x["namespace"] == "beat_position"][0]["data"]
            times = [float(x["time"]) for x in bp]
            nums = [int(x["value"]["position"]) for x in bp]
            style = base.split("_")[1].split("-")[0].rstrip("0123456789")
            items.append(dict(
                id=f"guitarset_{stem}", dataset=f"guitarset_{kind}", group=style,
                audio=os.path.join(a.guitarset_audio, stem + "_mic.wav"), beats=times, nums=nums, meter="4/4",
                trained=["small0", "final0"] if kind == "comp" else []))
    os.makedirs(a.work, exist_ok=True)
    json.dump(items, open(os.path.join(a.work, "items.json"), "w"))
    print(Counter(i["dataset"] for i in items))


def load_items(work):
    return json.load(open(os.path.join(work, "items.json")))


def load_audio(path, sr):
    import soundfile as sf
    import soxr
    x, r = sf.read(path, dtype="float64", always_2d=True)
    x = x.mean(1)
    if r != sr:
        x = soxr.resample(x, r, sr)
    return x


def moments_for(duration):
    m = []
    t = FIRST_MOMENT
    while t <= duration - 1.0 + 1e-9:
        m.append(t)
        t += 1.0
    return m


# ----------------------------------------------------------------------------- swift

def run_swift(a):
    items = load_items(a.work)
    for preset in a.presets:
        os.makedirs(os.path.join(a.work, "swift", preset), exist_ok=True)
    os.makedirs(os.path.join(a.work, "f32"), exist_ok=True)
    lines = []
    for it in items:
        raw = os.path.join(a.work, "f32", f"{it['id']}_{a.rate}.f32")
        if not os.path.exists(raw):
            load_audio(it["audio"], a.rate).astype("<f4").tofile(raw)
        for preset in a.presets:
            out = os.path.join(a.work, "swift", preset, it["id"] + ".jsonl")
            if not os.path.exists(out):
                lines.append(f"{raw} {out} {a.rate} {preset}")
    # split over a few processes
    n = max(1, a.jobs)
    procs = []
    for k in range(n):
        chunk = lines[k::n]
        if not chunk:
            continue
        lf = os.path.join(a.work, f"kclive_list_{k}.txt")
        open(lf, "w").write("\n".join(chunk) + "\n")
        procs.append(subprocess.Popen([a.kclive, lf]))
    for p in procs:
        p.wait()
    print(f"swift: ran {len(lines)} clip/preset runs")


def read_swift(path):
    moments, shown, done = [], [], None
    for line in open(path):
        d = json.loads(line)
        if "moment" in d:
            moments.append(d)
        elif "shownBeats" in d:
            shown = d["shownBeats"]
        elif "done" in d:
            done = d
    return moments, shown, done


# ----------------------------------------------------------------------------- beat this

def bt_postprocess(post, beat_logits, downbeat_logits):
    """Official minimal postprocessing on a batch (B, T) of logits -> lists of frame-time arrays."""
    import torch
    b, d = post(torch.as_tensor(beat_logits), torch.as_tensor(downbeat_logits))
    return list(b), list(d)


def bt_readout(beats, downbeats, now, prev_bpb, n_ibi=8):
    """App-style readout from one window's beats/downbeats (seconds, clip time)."""
    beats = np.asarray([x for x in beats if x <= now + 1e-9])
    bpm = period = last = None
    if len(beats) >= 3:
        ibis = np.diff(beats[-(n_ibi + 1):])
        period = float(np.median(ibis))
        if period > 0:
            bpm = 60.0 / period
        last = float(beats[-1])
    bpb = None
    dbs = [x for x in downbeats if x <= now + 1e-9]
    bars = []
    for d0, d1 in zip(dbs[:-1], dbs[1:]):
        bars.append(int(np.sum((beats >= d0 - 1e-6) & (beats < d1 - 1e-6))))
    bars = [x for x in bars if 1 <= x <= 12]
    if bars:
        c = Counter(bars)
        top = max(c.values())
        # most common bar length; on a tie, the most recent bar
        bpb = next(x for x in reversed(bars) if c[x] == top)
    if bpb is None:
        bpb = prev_bpb  # keep the last answer when this window holds no complete bar
    return bpm, bpb, last, period


def bt_readouts(live, meter="comb", decay=0.5):
    """Turns the stored per-moment Beat This! output into app readouts.

    tempo: 60 / median of the last 8 inter-beat intervals (needs 3 beats);
    last beat: newest picked beat; period: that median interval.
    meter "peaks": bar lengths between the official (snapped) downbeats, most common one,
      the last answer kept when a window holds no complete bar.
    meter "comb": for 2, 3 and 4 beats per bar and every phase, contrast of the mean downbeat
      probability on the bar grid against the other beats; scores decay by `decay` per
      readout so a few windows are pooled; the best bar length wins.
    """
    out = []
    prev_bpb = None
    acc = {2: 0.0, 3: 0.0, 4: 0.0}
    for x in live:
        now = x["t"]
        bpm, bpb_peaks, last, period = bt_readout(x["b"], x["d"], now, prev_bpb)
        prev_bpb = bpb_peaks
        bpb = bpb_peaks
        if meter == "comb":
            q = 1.0 / (1.0 + np.exp(-np.asarray(x["dp"], float)))
            for bb in acc:
                acc[bb] *= decay
                if len(q) >= 2 * bb:
                    best = -1.0
                    idx = np.arange(len(q))
                    for ph in range(bb):
                        on = (idx - ph) % bb == 0
                        best = max(best, q[on].mean() - q[~on].mean())
                    acc[bb] += best
            bpb = max(acc, key=acc.get) if any(v != 0.0 for v in acc.values()) else None
        out.append((now, now, bpm, bpb, last, period, None))
    return out


_W = {}


def _bt_worker_init(src, ckpts, threads):
    import torch
    torch.set_num_threads(threads)
    from bt_common import import_beat_this, load_model
    inference, postprocessor, _ = import_beat_this(src)
    _W["inference"] = inference
    _W["post"] = postprocessor.Postprocessor("minimal")
    _W["models"] = {name: load_model(src, path).eval() for name, path in ckpts}


def _bt_one(job):
    import torch
    from frontend_np import logmel
    it, names, windows, work, batch, right_pad, tag = job
    x = load_audio(it["audio"], 22050)
    spect = logmel(x)
    duration = len(x) / 22050
    moments = moments_for(duration)
    out_all = {}
    for name in names:
        out_path = os.path.join(work, "bt", name + tag, it["id"] + ".json")
        if os.path.exists(out_path):
            continue
        model = _W["models"][name]
        res = {"duration": duration, "moments": moments}
        with torch.inference_mode():
            # offline reference: official chunked whole-clip inference
            st = torch.tensor(spect)
            pred = _W["inference"].split_predict_aggregate(st, 1500, BORDER, "keep_first", model)
            ob, od = _W["post"](pred["beat"], pred["downbeat"])
            res["offline_beats"] = [float(v) for v in ob]
            res["offline_downbeats"] = [float(v) for v in od]
            for w in windows:
                n = int(round(w * FPS))
                wins = []
                starts = []
                for m in moments:
                    # newest frame whose 1024-sample window is complete at time m
                    last = int(np.floor((m * 22050 - 512) / 441))
                    s0 = last - n + 1
                    win = np.zeros((n + BORDER + right_pad, 128), np.float32)
                    lo = max(0, s0)
                    win[BORDER + (lo - s0): BORDER + n] = spect[lo: last + 1]
                    wins.append(win)
                    starts.append(s0)
                wins = torch.tensor(np.stack(wins))
                bl, dl = [], []
                for k in range(0, len(wins), batch):
                    o = model(wins[k: k + batch])
                    bl.append(o["beat"][:, BORDER: BORDER + n])
                    dl.append(o["downbeat"][:, BORDER: BORDER + n])
                bl = torch.cat(bl)
                dl = torch.cat(dl)
                pb, pd = _W["post"](bl, dl)
                live = []
                for k, (m, s0, b, d) in enumerate(zip(moments, starts, pb, pd)):
                    # downbeat logit at each picked beat (max within +-1 frame), for meter readouts
                    dlk = dl[k].numpy()
                    dp = [float(dlk[max(0, int(round(v * FPS)) - 1): int(round(v * FPS)) + 2].max()) for v in b]
                    live.append(dict(t=m, s0=s0, b=[round(float(v) + s0 / FPS, 4) for v in b],
                                     d=[round(float(v) + s0 / FPS, 4) for v in d], dp=[round(v, 3) for v in dp]))
                res[f"live_{w:g}"] = live
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        tmp = out_path + ".tmp"
        json.dump(res, open(tmp, "w"))
        os.replace(tmp, out_path)  # atomic: a killed run never leaves a half-written result
        out_all[name] = True
    return it["id"]


def run_beatthis(a):
    import multiprocessing as mp
    items = load_items(a.work)
    if a.datasets:
        items = [i for i in items if i["dataset"] in a.datasets]
    if a.limit:
        items = items[:: max(1, len(items) // a.limit)][: a.limit]
    ckpts = [tuple(c.split("=", 1)) for c in a.ckpt]
    names = [c[0] for c in ckpts]
    jobs = []
    for it in items:
        todo = [n for n in names if not os.path.exists(os.path.join(a.work, "bt", n + a.tag, it["id"] + ".json"))]
        if todo:
            jobs.append((it, todo, a.windows, a.work, a.batch, a.right_pad, a.tag))
    print(f"beatthis: {len(jobs)} clips to run with {names}", flush=True)
    ctx = mp.get_context("fork")
    with ctx.Pool(a.jobs, initializer=_bt_worker_init, initargs=(a.beat_this_src, ckpts, a.threads)) as pool:
        for k, done in enumerate(pool.imap_unordered(_bt_one, jobs)):
            if k % 20 == 0:
                print(f"  {k + 1}/{len(jobs)} {done}", flush=True)


# ----------------------------------------------------------------------------- scoring

class Truth:
    def __init__(self, it):
        self.beats = np.asarray(it["beats"], float)
        self.nums = np.asarray(it["nums"], int)
        down = self.beats[self.nums == 1] if len(self.nums) else np.array([])
        self.down = down
        self.bars = []  # (start, end, beats)
        for d0, d1 in zip(down[:-1], down[1:]):
            self.bars.append((d0, d1, int(np.sum((self.beats >= d0 - 1e-6) & (self.beats < d1 - 1e-6)))))

    def tempo(self, t):
        sel = self.beats[(self.beats >= t - 3.0) & (self.beats <= t + 3.0)]
        if len(sel) < 3:
            return None
        return 60.0 / float(np.median(np.diff(sel)))

    def bpb(self, t):
        if not self.bars:
            return None
        for d0, d1, n in self.bars:
            if d0 <= t < d1:
                return n
        if t < self.bars[0][0]:
            return self.bars[0][2]
        return self.bars[-1][2]

    def has_next(self, t):
        return np.any((self.beats > t) & (self.beats <= t + 3.0))

    def phase_ok(self, now, last, period):
        if last is None or period is None or period <= 0 or last > now + 0.5:
            return False
        k = np.floor((now - last) / period) + 1
        nxt = last + k * period
        return bool(np.min(np.abs(self.beats - nxt)) <= PHASE_TOL)


def tempo_ok(bpm, ref, octave):
    if bpm is None or ref is None:
        return False
    cands = (ref, 2 * ref, ref / 2) if octave else (ref,)
    return any(abs(bpm - c) <= TEMPO_TOL * c for c in cands)


def score_clip(truth, readouts):
    """readouts: list of (moment, now, bpm, bpb, last, period, compound). Returns per-clip dict."""
    rows = []
    first = None
    for m, now, bpm, bpb, last, period, compound in readouts:
        ref = truth.tempo(m)
        if ref is None or not truth.has_next(m):
            continue
        ts = tempo_ok(bpm, ref, False)
        rows.append(dict(
            m=m, tempo_strict=ts, tempo_octave=tempo_ok(bpm, ref, True),
            bpb=(bpb is not None and truth.bpb(m) is not None and bpb == truth.bpb(m)),
            phase=truth.phase_ok(now, last, period), compound=compound, answered=bpm is not None))
        if ts and first is None:
            first = m
    return rows, first


def shown_pulses(shown):
    """The pulses the screen actually shows: a re-estimate can move lastBeatAt back in time,
    but a beat that is already past is not pulsed again, so keep only forward steps."""
    out = []
    for b, i in shown:
        if not out or b > out[-1][0] + 0.03:
            out.append((b, i))
    return out


def offline_f(ref, est):
    import mir_eval
    r = mir_eval.beat.trim_beats(np.asarray(ref, float))
    e = mir_eval.beat.trim_beats(np.asarray(est, float))
    if len(r) == 0:
        return None
    return mir_eval.beat.f_measure(r, e)


def summarize(name, clip_rows, firsts, fs, dfs):
    allrows = [r for rows in clip_rows for r in rows]
    n = len(allrows)
    if n == 0:
        return None

    def pct(k):
        return 100.0 * sum(r[k] for r in allrows) / n

    fvals = sorted(firsts.values(), key=lambda v: np.inf if v is None else v)
    found = [v for v in fvals if v is not None]
    never = sum(1 for v in fvals if v is None)
    med = np.median([np.inf if v is None else v for v in fvals]) if fvals else None
    return dict(name=name, clips=len(clip_rows), moments=n, tempo_strict=pct("tempo_strict"),
                tempo_octave=pct("tempo_octave"), bpb=pct("bpb"), phase=pct("phase"),
                ttf_median=med, never=never, found=len(found),
                f=100.0 * np.mean([v for v in fs if v is not None]) if fs else None,
                df=100.0 * np.mean([v for v in dfs if v is not None]) if dfs else None)


def fmt(s):
    ttf = "never" if s["ttf_median"] is None or not np.isfinite(s["ttf_median"]) else f"{s['ttf_median']:.0f} s"
    f = "" if s["f"] is None else f"{s['f']:.1f}"
    df = "" if s["df"] is None else f"{s['df']:.1f}"
    return (f"| {s['name']} | {s['clips']} | {s['moments']} | {s['tempo_strict']:.1f} | {s['tempo_octave']:.1f} | "
            f"{s['bpb']:.1f} | {s['phase']:.1f} | {ttf} | {s['never']} | {f} | {df} |")


HEADER = ("| system | clips | moments | tempo ±4% | tempo ±4% (×2, ÷2 ok) | beats/bar | phase ±70 ms | "
          "median time to 1st correct tempo | clips never correct | offline beat F | offline downbeat F |\n"
          "|---|---|---|---|---|---|---|---|---|---|---|")


def score(a):
    items = load_items(a.work)
    systems = []  # (label, kind, key, W)
    for preset in a.presets:
        systems.append((f"KeyCatch Swift ({preset})", "swift", preset, None))
    for c in a.ckpts:
        for w in a.windows:
            systems.append((f"Beat This {c}, W={w:g} s", "bt", c, w))
    groups = defaultdict(list)
    for it in items:
        ds = it["dataset"]
        if ds == "gtzan":
            if it.get("random_sample"):
                groups["GTZAN (stratified random sample)"].append(it)
            if it["meter"] == "3/4":
                groups["GTZAN 3/4 (all)"].append(it)
            if it.get("ternary"):
                groups["GTZAN 12/8 + 9/8 (all, ternary)"].append(it)
        elif ds == "ballroom":
            groups["Ballroom val split (all styles)"].append(it)
            if "Waltz" in it["group"]:
                groups["Ballroom val split, Waltz + Viennese Waltz"].append(it)
        else:
            groups[{"guitarset_comp": "GuitarSet comp (val split, mic)", "guitarset_solo": "GuitarSet solo (mic)"}[ds]].append(it)
    compound_stats = defaultdict(Counter)
    per_genre = defaultdict(dict)
    print(f"Moments every 1 s from {FIRST_MOMENT:g} s; tempo tolerance {TEMPO_TOL:.0%}; phase tolerance {PHASE_TOL * 1000:.0f} ms. "
          "Percentages are over all scored moments of all clips. Offline F: mir_eval, first 5 s trimmed.\n")
    for gname, its in groups.items():
        print(f"### {gname}\n")
        print(HEADER)
        for label, kind, key, w in systems:
            if a.preset_for and kind == "swift":
                want = "guitar" if its[0]["dataset"].startswith("guitarset") else "band"
                if key != want and not a.all_presets:
                    continue
            clip_rows, firsts, fs, dfs = [], {}, [], []
            seen = set()
            missing = 0
            for it in its:
                truth = Truth(it)
                if kind == "swift":
                    p = os.path.join(a.work, "swift", key, it["id"] + ".jsonl")
                    if not os.path.exists(p):
                        missing += 1
                        continue
                    ms, shown, _ = read_swift(p)
                    ro = [(d["moment"], d["t"], d["bpm"], d["beatsPerBar"] or None,
                           None if d["lastBeat"] < 0 else d["lastBeat"], d["periodMs"] / 1000.0 if d["periodMs"] > 0 else None,
                           d["compound"]) for d in ms]
                    pulses = shown_pulses(shown)
                    f = offline_f(truth.beats, [b for b, _ in pulses])
                    df = offline_f(truth.down, [b for b, i in pulses if i == 0]) if len(truth.down) else None
                else:
                    p = os.path.join(a.work, "bt", key, it["id"] + ".json")
                    if not os.path.exists(p):
                        missing += 1
                        continue
                    d = json.load(open(p))
                    live = d.get(f"live_{w:g}")
                    if live is None:
                        missing += 1
                        continue
                    ro = bt_readouts(live, a.meter)
                    f = offline_f(truth.beats, d["offline_beats"])
                    df = offline_f(truth.down, d["offline_downbeats"]) if len(truth.down) else None
                rows, first = score_clip(truth, ro)
                if not rows:
                    continue
                clip_rows.append(rows)
                firsts[it["id"]] = first
                fs.append(f)
                dfs.append(df)
                if kind == "swift":
                    for r in rows:
                        compound_stats[(gname, key)]["moments"] += 1
                        compound_stats[(gname, key)]["compound"] += int(bool(r["compound"]))
                    if it["dataset"] == "gtzan" and it.get("random_sample"):
                        per_genre[(label, it["group"])].setdefault("rows", []).extend(rows)
                elif it["dataset"] == "gtzan" and it.get("random_sample"):
                    per_genre[(label, it["group"])].setdefault("rows", []).extend(rows)
            s = summarize(label + (f" ({missing} clips missing)" if missing else ""), clip_rows, firsts, fs, dfs)
            if s:
                trained = {c for it in its for c in it.get("trained", [])}
                if kind == "bt" and key in trained:
                    s["name"] += " **(model trained on these tracks)**"
                print(fmt(s))
        print()
    print("### Swift tracker: share of scored moments shown as compound (6/8, 9/8, 12/8)\n")
    print("| group | preset | moments | compound shown |\n|---|---|---|---|")
    for (g, p), c in compound_stats.items():
        print(f"| {g} | {p} | {c['moments']} | {100.0 * c['compound'] / max(1, c['moments']):.1f}% |")
    print()
    if per_genre:
        print("### GTZAN random sample per genre: tempo ±4% (×2 ok) / phase\n")
        genres = sorted({g for _, g in per_genre})
        labels = []
        for (l, _g) in per_genre:
            if l not in labels:
                labels.append(l)
        print("| system | " + " | ".join(genres) + " |")
        print("|---|" + "---|" * len(genres))
        for l in labels:
            cells = []
            for g in genres:
                rows = per_genre.get((l, g), {}).get("rows", [])
                if rows:
                    cells.append(f"{100 * np.mean([r['tempo_octave'] for r in rows]):.0f} / {100 * np.mean([r['phase'] for r in rows]):.0f}")
                else:
                    cells.append("")
            print(f"| {l} | " + " | ".join(cells) + " |")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--gtzan-audio")
    p.add_argument("--gtzan-ann")
    p.add_argument("--gtzan-rhythm", help="GTZAN-Rhythm v2 stats.csv (meter, ternary, swing)")
    p.add_argument("--gtzan-per-genre", type=int, default=30)
    p.add_argument("--ballroom-audio")
    p.add_argument("--ballroom-ann")
    p.add_argument("--ballroom-split")
    p.add_argument("--guitarset-audio")
    p.add_argument("--guitarset-ann")
    p.add_argument("--guitarset-split")
    p.add_argument("--guitarset-solo-every", type=int, default=3, help="take every n-th solo take (3 -> 60 of 180)")
    p = sub.add_parser("swift")
    p.add_argument("--kclive", required=True)
    p.add_argument("--rate", type=int, default=44100)
    p.add_argument("--presets", nargs="+", default=["band", "guitar"])
    p.add_argument("--jobs", type=int, default=4)
    p = sub.add_parser("beatthis")
    p.add_argument("--beat-this-src", required=True)
    p.add_argument("--ckpt", nargs="+", required=True, help="name=path")
    p.add_argument("--windows", nargs="+", type=float, default=[6, 8, 12])
    p.add_argument("--datasets", nargs="*")
    p.add_argument("--jobs", type=int, default=4)
    p.add_argument("--threads", type=int, default=1)
    p.add_argument("--right-pad", type=int, default=BORDER, help="zero frames after the newest frame (official: 6)")
    p.add_argument("--tag", default="", help="suffix for the output folder, for variants")
    p.add_argument("--limit", type=int, default=0, help="only the first N clips (after --datasets)")
    p.add_argument("--batch", type=int, default=1, help="windows per forward pass; 1 was fastest on this CPU")
    p = sub.add_parser("score")
    p.add_argument("--presets", nargs="+", default=["band", "guitar"])
    p.add_argument("--ckpts", nargs="+", default=["small0", "final0"])
    p.add_argument("--windows", nargs="+", type=float, default=[6, 8, 12])
    p.add_argument("--preset-for", action="store_true", default=True, help="Band for GTZAN/Ballroom, Guitar for GuitarSet")
    p.add_argument("--all-presets", action="store_true")
    p.add_argument("--meter", default="comb", choices=["comb", "peaks"], help="Beat This! beats-per-bar readout")
    a = ap.parse_args()
    {"prepare": prepare, "swift": run_swift, "beatthis": run_beatthis, "score": score}[a.cmd](a)


if __name__ == "__main__":
    main()
