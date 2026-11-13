"""Fetches the English half of the Children's Song Dataset (Zenodo 4785016, CC BY-NC-SA 4.0)
by range requests into its zip: note CSVs, MIDI, metadata, and each vocal WAV stored as mono FLAC
(the WAV is checked against the zip's CRC on extraction, then deleted).

usage: python -I fetch_csd.py OUTDIR
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import soundfile as sf  # noqa: E402

import remote_zip  # noqa: E402

URL = "https://zenodo.org/records/4785016/files/CSD.zip?download=1"


def main():
    import io
    import zipfile
    out = sys.argv[1]
    z = zipfile.ZipFile(io.BufferedReader(remote_zip.RangeFile(URL), buffer_size=1 << 20))
    for i in z.infolist():
        n = i.filename
        if not n.startswith("CSD/english/") or i.is_dir():
            continue
        if n.endswith(".wav"):
            flac = os.path.join(out, "flac", os.path.basename(n)[:-4] + ".flac")
            if os.path.exists(flac):
                continue
            os.makedirs(os.path.dirname(flac), exist_ok=True)
            data = z.read(i)  # CRC-checked
            x, r = sf.read(io.BytesIO(data), always_2d=True)
            sf.write(flac + ".part", x.mean(1), r, format="FLAC", subtype="PCM_16")
            os.replace(flac + ".part", flac)
            print("got", n, r, x.shape, flush=True)
        elif n.endswith((".csv", ".mid", ".json")):
            dest = os.path.join(out, n)
            if os.path.exists(dest):
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            open(dest, "wb").write(z.read(i))


if __name__ == "__main__":
    main()
