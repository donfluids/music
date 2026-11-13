"""List or extract members of a remote zip through HTTP range requests (no full download).

usage: python -I remote_zip.py URL list
       python -I remote_zip.py URL extract OUTDIR PATTERN...   (fnmatch patterns on member names)
"""
import fnmatch
import io
import os
import sys
import urllib.request
import zipfile


class RangeFile(io.RawIOBase):
    def __init__(self, url):
        self.url = url
        req = urllib.request.Request(url, method="HEAD")
        with urllib.request.urlopen(req) as r:
            self.size = int(r.headers["Content-Length"])
        self.pos = 0

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else (self.pos + off if whence == 1 else self.size + off)
        return self.pos

    def read(self, n=-1):
        if n is None or n < 0:
            n = self.size - self.pos
        n = min(n, self.size - self.pos)
        if n <= 0:
            return b""
        req = urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{self.pos + n - 1}"})
        with urllib.request.urlopen(req) as r:
            data = r.read()
        self.pos += len(data)
        return data

    def readinto(self, b):
        d = self.read(len(b))
        b[: len(d)] = d
        return len(d)


def main():
    url, cmd = sys.argv[1], sys.argv[2]
    z = zipfile.ZipFile(io.BufferedReader(RangeFile(url), buffer_size=1 << 20))
    if cmd == "list":
        for i in z.infolist():
            print(i.filename, i.file_size, f"{i.CRC:08x}")
        return
    out, pats = sys.argv[3], sys.argv[4:]
    for i in z.infolist():
        if i.is_dir() or not any(fnmatch.fnmatch(i.filename, p) for p in pats):
            continue
        dest = os.path.join(out, i.filename)
        if os.path.exists(dest) and os.path.getsize(dest) == i.file_size:
            continue
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with z.open(i) as src, open(dest + ".part", "wb") as f:  # zipfile checks the CRC on read
            while True:
                b = src.read(1 << 20)
                if not b:
                    break
                f.write(b)
        os.replace(dest + ".part", dest)
        print("got", i.filename, flush=True)


if __name__ == "__main__":
    main()
