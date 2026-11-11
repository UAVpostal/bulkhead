#!/usr/bin/env python3
"""Turns a hosts-format block list into the compact file the app loads.

Usage: make_blocklist.py HOSTS_FILE OUTPUT_FILE

Output: "BLK1", a 4-byte count, then that many 64-bit FNV-1a hashes of the
host names, sorted as signed numbers (big-endian). Blocklist.java looks a host
up by hashing it the same way and binary-searching.
"""
import re
import struct
import sys

HOST = re.compile(r"^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$")
IPV4 = re.compile(r"^\d+(\.\d+){3}$")
MASK = (1 << 64) - 1


def fnv1a(host):
    h = 0xCBF29CE484222325
    for b in host.encode("ascii"):
        h ^= b
        h = (h * 0x100000001B3) & MASK
    return h - (1 << 64) if h >= (1 << 63) else h


def hosts(path):
    out = set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.split("#", 1)[0].split()
            if len(parts) < 2 or parts[0] not in ("0.0.0.0", "127.0.0.1"):
                continue
            for name in parts[1:]:
                name = name.lower().rstrip(".")
                if HOST.match(name) and not IPV4.match(name) and not name.endswith(".localdomain"):
                    out.add(name)
    return out


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    names = hosts(sys.argv[1])
    if len(names) < 1000:
        sys.exit("only %d hosts found; refusing to write a list that small" % len(names))
    hashes = sorted({fnv1a(n) for n in names})
    with open(sys.argv[2], "wb") as f:
        f.write(b"BLK1")
        f.write(struct.pack(">i", len(hashes)))
        f.write(struct.pack(">%dq" % len(hashes), *hashes))
    print("wrote %d hosts to %s" % (len(hashes), sys.argv[2]))


if __name__ == "__main__":
    main()
