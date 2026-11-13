"""Reference for KeyCatch's live Beat This! reading, as the apps run it (Swift: BeatThis.swift).

Each second from 4 s, the newest 400 complete log-mel frames (8 s, zeros before listening
began) go through the network with 6 zero frames on each side; then, on that window's logits:

  peaks      the official minimal postprocessing (max within +-3 frames, logit > 0, adjacent
             peaks merged by their running mean, downbeats moved to the nearest beat)
  Reader     live_eval.bt_readouts: tempo from the median of the last 8 intervals, last beat,
             beats per bar by the downbeat comb (scores halve at each reading); plus what the
             screen needs on top: the bar position of the last beat (the comb's best phase in
             this window for the chosen bar length) and "locked" (three readings in a row
             within 4 % of each other)
  Feel       the slow simple-or-compound decision on the Beat This! beat grid (README section 5)

make_fixtures.py checks Reader against live_eval.bt_readouts and writes the Swift test data.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

FPS = 50
BORDER = 6
FRAMES = 400  # 8 s
INPUT_FRAMES = FRAMES + 2 * BORDER
BARS = (2, 3, 4)


def peak_frames(logits):
    """Frames picked from one logit sequence (float32), as Postprocessor('minimal'): a frame
    equal to the maximum over the 7 frames around it (outside counts as -inf) with logit > 0;
    runs of picked frames at most one frame from the running mean are replaced by their mean."""
    x = np.asarray(logits, np.float32)
    n = len(x)
    picked = []
    for t in range(n):
        m = x[max(0, t - 3): min(n, t + 4)].max()
        if x[t] == m and x[t] > 0:
            picked.append(t)
    out = []
    if not picked:
        return out
    p = float(picked[0])
    c = 1
    for p2 in picked[1:]:
        if p2 - p <= 1:
            c += 1
            p += (p2 - p) / c
        else:
            out.append(p)
            p = float(p2)
            c = 1
    out.append(p)
    return out


def postprocess(beat_logits, downbeat_logits):
    """Window-local beat and downbeat times in seconds, exactly as the official postprocessor."""
    beat = np.asarray(peak_frames(beat_logits), float) / FPS
    down = np.asarray(peak_frames(downbeat_logits), float) / FPS
    if len(beat) > 0:
        for i, d in enumerate(down):
            down[i] = beat[int(np.argmin(np.abs(beat - d)))]
    return beat, np.unique(down)


def downbeat_at_beats(beat_local, downbeat_logits):
    """Downbeat logit at each picked beat, max within +-1 frame (live_eval)."""
    dl = np.asarray(downbeat_logits, np.float32)
    out = []
    for v in beat_local:
        r = int(round(v * FPS))
        out.append(float(dl[max(0, r - 1): r + 2].max()))
    return out


class Reader:
    """One reading per window, carrying the comb scores and the lock from reading to reading."""

    def __init__(self, decay=0.5, n_ibi=8):
        self.decay = decay
        self.n_ibi = n_ibi
        self.acc = {b: 0.0 for b in BARS}
        self.recent = []  # bpm of the last readings (None for none)
        self.prev = None  # (last beat, bar index, period) of the previous reading

    def read(self, now, s0, beat_logits, downbeat_logits):
        """now: clip time of the window's end; s0: clip frame of the window's first frame;
        logits: the window's FRAMES frames (borders already removed)."""
        b_local, d_local = postprocess(beat_logits, downbeat_logits)
        beats = np.asarray([float(v) + s0 / FPS for v in b_local])
        downs = [float(v) + s0 / FPS for v in d_local]
        dp = downbeat_at_beats(b_local, downbeat_logits)
        beats_now = np.asarray([x for x in beats if x <= now + 1e-9])
        bpm = period = last = None
        if len(beats_now) >= 3:
            ibis = np.diff(beats_now[-(self.n_ibi + 1):])
            period = float(np.median(ibis))
            if period > 0:
                bpm = 60.0 / period
            last = float(beats_now[-1])
        q = 1.0 / (1.0 + np.exp(-np.asarray(dp, float)))
        phases = {}
        for bb in BARS:
            self.acc[bb] *= self.decay
            if len(q) >= 2 * bb:
                best = -1.0
                idx = np.arange(len(q))
                for ph in range(bb):
                    on = (idx - ph) % bb == 0
                    v = q[on].mean() - q[~on].mean()
                    if v > best:
                        best = v
                        phases[bb] = ph
                self.acc[bb] += best
        bpb = max(self.acc, key=self.acc.get) if any(v != 0.0 for v in self.acc.values()) else None
        # Bar position of the newest beat: from this window's comb phase for the chosen bar
        # length; without one, the previous reading's position carried forward by whole beats.
        index = 0
        if bpb is not None and last is not None:
            if bpb in phases:
                index = (len(q) - 1 - phases[bpb]) % bpb
            elif self.prev is not None and self.prev[2]:
                index = (self.prev[1] + int(math.floor((last - self.prev[0]) / self.prev[2] + 0.5))) % bpb
        self.recent = (self.recent + [bpm])[-3:]
        locked = len(self.recent) == 3 and all(v is not None for v in self.recent) and max(self.recent) / min(self.recent) < 1.04
        if last is not None:
            self.prev = (last, index, period)
        strength = float(np.mean(1.0 / (1.0 + np.exp(-np.asarray([beat_logits[int(round(v * FPS))] for v in b_local], float))))) if len(b_local) else 0.0
        return dict(t=now, beats=[float(x) for x in beats], downbeats=downs, dp=dp, bpm=bpm, period=period, last=last,
                    bpb=bpb, index=index, locked=locked, scores=dict(self.acc), strength=strength)


class Feel:
    """Simple or compound, decided slowly on the Beat This! beat grid (README section 5).

    At each reading with a tempo: MeterDetector's four sums over the 8 s onset history (onset
    at the beat, halfway, a third and two thirds of the way to the next beat), divided by the
    number of beats, are added to a pool that keeps 0.95 of itself per reading (about 20 s).
    Compound is shown only after the pooled evidence has been strong for 5 readings in a row:
    at least 10 readings' worth pooled, the thirds more than twice the halfway point, both
    thirds within a factor 2 of each other, and above a quarter of the beat. It goes back to
    simple after 5 readings in a row without a third above 1.1 x halfway. A beat period more
    than 8 % from the pool's (smoothed) period empties the pool and shows simple."""

    POOL = 0.95
    MIN_WEIGHT = 10.0
    ON_RATIO = 2.0
    OFF_RATIO = 1.1
    BALANCE = 0.5
    BEAT_SHARE = 0.25
    HOLD = 5
    PERIOD_TOLERANCE = 0.08

    def __init__(self):
        self.reset()

    def reset(self):
        self.acc = np.zeros(4)
        self.weight = 0.0
        self.period = 0.0
        self.compound = False
        self.on = 0
        self.off = 0

    def update(self, energies, period_frames):
        """energies: per-beat (beat, half, first third, second third) of this reading's history."""
        if self.period > 0 and abs(period_frames / self.period - 1.0) > self.PERIOD_TOLERANCE:
            p = period_frames
            self.reset()
            self.period = p
        self.period = period_frames if self.period == 0 else 0.8 * self.period + 0.2 * period_frames
        self.acc = self.POOL * self.acc + np.asarray(energies, float)
        self.weight = self.POOL * self.weight + 1.0
        beat, half, first, second = self.acc
        third = 0.5 * (first + second)
        both = min(first, second) > self.BALANCE * max(first, second)
        strong = self.weight >= self.MIN_WEIGHT and both and third > self.ON_RATIO * half and third > self.BEAT_SHARE * beat
        keep = both and third > self.OFF_RATIO * half
        self.on = self.on + 1 if strong else 0
        self.off = self.off + 1 if not keep else 0
        if not self.compound and self.on >= self.HOLD:
            self.compound = True
        if self.compound and self.off >= self.HOLD:
            self.compound = False
        return self.compound


def feel_energies(onsets, rate, hop, now, last, period):
    """The per-beat sums for one reading from the raw onset signal (one value per hop, as
    KeyCatchCore's OnsetDetector gives it), or None when the history is too short."""
    from compound_proto import detrend_rate, subdivision_energies
    fps = rate / hop
    capacity = int(math.floor(8.0 * fps + 0.5))
    h_end = int(math.floor(now * rate / hop)) - 1
    h0 = max(0, h_end - capacity + 1)
    hist = detrend_rate(np.asarray(onsets[h0: h_end + 1], np.float64), fps)
    newest = last * rate / hop - 1 - h0
    pf = period * rate / hop
    if pf < 4.0 or len(hist) < 3 * fps or newest < 0:
        return None, pf
    e = subdivision_energies(hist, newest, pf)
    return e / max(1.0, math.floor(newest / pf)), pf
