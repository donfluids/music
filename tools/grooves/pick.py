"""Scores every 4-bar stretch of each candidate performance and prints the best per app style."""
import sys, math, json
sys.path.insert(0, sys.argv[2])
import gmd
root = sys.argv[1]
ROWS = gmd.rows(root)

# App style -> (genres, time signature, bpm range, quarters per app beat, app beats per bar, backbeat?, swing wanted)
STYLES = {
    'WORSHIP_SLOW':   (['soul', 'pop', 'rock', 'gospel', 'funk'], '4-4', (60, 92), 1.0, 4, True, None),
    'ROCK_8':         (['rock'], '4-4', (100, 136), 1.0, 4, True, None),
    'POWER_ROCK':     (['punk', 'rock'], '4-4', (132, 210), 1.0, 4, True, None),
    'POP_16':         (['pop', 'funk', 'soul'], '4-4', (84, 116), 1.0, 4, True, None),
    'GOSPEL_SHUFFLE': (['gospel', 'blues', 'neworleans', 'soul', 'funk', 'jazz', 'rock'], '4-4', (80, 140), 1.0, 4, True, 'swing'),
    'WORSHIP_68':     (['rock', 'jazz', 'latin', 'pop', 'soul'], '6-8', (40, 200), 1.5, 2, False, None),
    'WALTZ':          (['jazz', 'latin', 'rock', 'pop'], '3-4', (60, 200), 1.0, 3, False, None),
}
BARS = 4
CYMBAL_8THS = {'ROCK_8': ('HAT',), 'POWER_ROCK': ('HAT', 'OPEN_HAT', 'RIDE', 'CRASH'), 'POP_16': ('HAT',), 'WORSHIP_SLOW': ('HAT', 'RIDE')}
VOICES = ['KICK', 'SNARE', 'RIM', 'HAT', 'OPEN_HAT', 'RIDE', 'CRASH', 'TOM_HI', 'TOM_LO']

def grid(hits, start, quarters, steps):
    """Velocity grid over [start, start+quarters) at `steps` per quarter, per voice."""
    g = {v: [0.0] * int(quarters * steps) for v in VOICES}
    for t, d, vel in hits:
        k = int(round((t - start) * steps))
        if 0 <= k < len(g[d]): g[d][k] = max(g[d][k], vel / 127)
    return g

def cosine(a, b):
    x = sum(sum(p * q for p, q in zip(a[v], b[v])) for v in VOICES)
    na = math.sqrt(sum(p * p for v in VOICES for p in a[v])); nb = math.sqrt(sum(q * q for v in VOICES for q in b[v]))
    return x / (na * nb + 1e-9)

def swing_ratio(hits, start, quarters):
    """Where the off-beat 8ths sit within the beat: 0.5 straight, 0.67 triplet swing."""
    pos = [((t - start) % 1.0) for t, d, v in hits if d in ('HAT', 'RIDE') and start <= t < start + quarters]
    off = [p for p in pos if 0.4 < p < 0.8]
    return sum(off) / len(off) if off else 0.5

def score(style, r, hits):
    genres, ts, (lo, hi), qpb, bpb, backbeat, swing = STYLES[style]
    bar_q = qpb * bpb
    best = None
    total_q = hits[-1][0] if hits else 0
    nbars = int(total_q // bar_q)
    for b in range(0, nbars - 2 * BARS + 1):
        s = b * bar_q
        win = [h for h in hits if s - 0.15 <= h[0] < s + BARS * bar_q - 0.15]
        if not win: continue
        # No fills inside the loop: no toms, crash only at the very start.
        if any(d in ('TOM_HI', 'TOM_LO') for t, d, v in win): continue
        if any(d == 'CRASH' and t - s > 0.2 for t, d, v in win): continue
        per_bar = len(win) / BARS
        if not (8 <= per_bar <= 48): continue
        # Straight styles want a steady cymbal: at least six hat or ride 8ths a bar, and no shuffles.
        if style in CYMBAL_8THS:
            want = CYMBAL_8THS[style]
            cym = [h for h in win if h[1] in want]
            eighths = len({round((t - s) * 2) for t, d, v in cym})
            if eighths < 6 * BARS: continue
            if 'shuffle' in r['style'] or 'swing' in r['style']: continue
        # Kick on each bar's downbeat (or close to it).
        downs = sum(1 for k in range(BARS) if any(d == 'KICK' and abs(t - (s + k * bar_q)) < 0.12 for t, d, v in win))
        if downs < BARS - 1: continue
        sc = 0.0
        if backbeat:
            bb = sum(1 for k in range(BARS) for beat in (1, 3) if any(d in ('SNARE', 'RIM') and abs(t - (s + k * bar_q + beat)) < 0.12 for t, d, v in win))
            if bb < 2 * BARS - 1: continue
        g1 = grid(hits, s, BARS * bar_q, 4); g2 = grid(hits, s + BARS * bar_q, BARS * bar_q, 4)
        rep = cosine(g1, g2)
        sc += rep
        sw = swing_ratio(hits, s, BARS * bar_q)
        if swing == 'swing':
            if sw < 0.6: continue
            sc += 0.5 * min(1, (sw - 0.6) / 0.07)
        elif sw > 0.58:
            sc -= 0.5  # straight styles want straight 8ths
        if best is None or sc > best[0]: best = (sc, s, rep, sw, per_bar)
    return best

def show(style, r, hits, s):
    genres, ts, rng, qpb, bpb, backbeat, swing = STYLES[style]
    bar_q = qpb * bpb
    steps = 4 if qpb == 1.0 else 2  # 16ths in simple time, 8ths in 6/8
    g = grid(hits, s, BARS * bar_q, steps)
    per_bar = int(bar_q * steps)
    lines = []
    for v in VOICES:
        if not any(g[v]): continue
        cells = ''.join(' ' if x == 0 else ('.' if x < 0.35 else ('o' if x < 0.7 else 'X')) for x in g[v])
        cells = '|'.join(cells[i:i + per_bar] for i in range(0, len(cells), per_bar))
        lines.append('      %-8s|%s|' % (v, cells))
    return '\n'.join(lines)

if __name__ == '__main__':
    results = {}
    only = sys.argv[4].split(',') if len(sys.argv) > 4 else list(STYLES)
    for style, (genres, ts, (lo, hi), *_rest) in STYLES.items():
        if style not in only: continue
        cands = []
        for r in ROWS:
            if r['beat_type'] != 'beat' or r['time_signature'] != ts: continue
            if r['style'].split('/')[0] not in genres: continue
            if not (lo <= int(r['bpm']) <= hi): continue
            hits = gmd.read(root, r)
            b = score(style, r, hits)
            if b: cands.append((b[0], r, b, hits))
        cands.sort(key=lambda c: -c[0])
        print('== %s: %d candidates' % (style, len(cands)))
        for sc, r, b, hits in cands[:4]:
            print('   %.2f %s %s bpm %s  loop-likeness %.2f swing %.2f hits/bar %.0f  start q%.1f' % (sc, r['id'], r['style'], r['bpm'], b[2], b[3], b[4], b[1]))
            print(show(style, r, hits, b[1]))
        results[style] = [(r['id'], b[1]) for sc, r, b, hits in cands[:3]]
    json.dump(results, open(sys.argv[3], 'w'), indent=1)
