"""Fetches one language half of the Children's Song Dataset (Zenodo 4785016, CC BY-NC-SA 4.0) by
range requests into its zip, as tools/skey/fetch_csd.py does for English: note CSVs, metadata,
and (unless --notes-only, or only for the recordings named with --only) each vocal WAV stored as
mono FLAC (checked against the zip's CRC on extraction).

usage: python -I fetch_csd_lang.py korean OUTDIR [--notes-only] [--only NAMES.txt]
"""
import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "skey"))  # remote_zip.py from tools/skey
import remote_zip  # noqa: E402

URL = "https://zenodo.org/records/4785016/files/CSD.zip?download=1"


def main():
    lang, out = sys.argv[1], sys.argv[2]
    notes_only = "--notes-only" in sys.argv
    only = None
    if "--only" in sys.argv:
        only = set(open(sys.argv[sys.argv.index("--only") + 1]).read().split())
    z = zipfile.ZipFile(io.BufferedReader(remote_zip.RangeFile(URL), buffer_size=1 << 20))
    for i in z.infolist():
        n = i.filename
        if not n.startswith(f"CSD/{lang}/") or i.is_dir():
            continue
        if n.endswith(".wav"):
            stem = os.path.basename(n)[:-4]
            if notes_only or (only is not None and stem not in only):
                continue
            import soundfile as sf
            flac = os.path.join(out, "flac", stem + ".flac")
            if os.path.exists(flac):
                continue
            os.makedirs(os.path.dirname(flac), exist_ok=True)
            data = z.read(i)  # CRC-checked
            x, r = sf.read(io.BytesIO(data), always_2d=True)
            sf.write(flac + ".part", x.mean(1), r, format="FLAC", subtype="PCM_16")
            os.replace(flac + ".part", flac)
            print("got", n, r, x.shape, flush=True)
        elif n.endswith((".csv", ".json", ".txt")):
            dest = os.path.join(out, n)
            if os.path.exists(dest):
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            open(dest, "wb").write(z.read(i))


if __name__ == "__main__":
    main()
