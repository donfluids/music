"""Reads Groove MIDI Dataset performances into hits measured in quarter notes."""
import csv, os, mido

# Roland TD-11 note numbers as used by the Groove MIDI Dataset, mapped to the app's drums.
DRUMS = {36: 'KICK', 38: 'SNARE', 40: 'SNARE', 37: 'RIM', 42: 'HAT', 22: 'HAT', 44: 'HAT',
         46: 'OPEN_HAT', 26: 'OPEN_HAT', 49: 'CRASH', 55: 'CRASH', 57: 'CRASH', 52: 'CRASH',
         51: 'RIDE', 59: 'RIDE', 53: 'RIDE', 48: 'TOM_HI', 50: 'TOM_HI', 45: 'TOM_HI', 47: 'TOM_HI',
         43: 'TOM_LO', 58: 'TOM_LO'}
# Pedal hi-hat and ride bell sit a little apart in loudness.
SCALE = {44: 0.6, 53: 1.15}

def read(root, row):
    mid = mido.MidiFile(os.path.join(root, row['midi_filename']))
    tpb = mid.ticks_per_beat
    hits = []
    for track in mid.tracks:
        t = 0
        for msg in track:
            t += msg.time
            if msg.type == 'note_on' and msg.velocity > 0 and msg.note in DRUMS:
                hits.append((t / tpb, DRUMS[msg.note], msg.velocity * SCALE.get(msg.note, 1.0)))
    hits.sort()
    return hits

def rows(root):
    return list(csv.DictReader(open(os.path.join(root, 'info.csv'))))
