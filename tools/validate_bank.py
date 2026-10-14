#!/usr/bin/env python3
"""Granja de validación: genera candidatos con gen_algo, los corre en Pd sin pantalla junto al
master, descarta los que fallan (errores, mudos, NaN), ajusta el nivel y escribe los que pasan en
pd/droidnoise/bank/<ROMANO>/ a partir del primer número libre.

Uso: python3 tools/validate_bank.py CANTIDAD [--seed N] [--jobs 4]
"""
import argparse
import math
import re
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_algo  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
LIB = ROOT / "pd" / "droidnoise" / "lib"
BANK = ROOT / "pd" / "droidnoise" / "bank"
MASTER = ROOT / "app" / "src" / "main" / "assets" / "master.pd"

QUIET = "ori 0 20 10; light 120; gyro 0.5 0.5 0"
WILD = ("ori 140 -50 -60; light 2; gyro 4 4 0; acc 0 0 0; acc 30 0 0; "
        "dn-f2 0.9; dn-f3 0.1; dn-f4 0.8; dn-f5 0.2; dn-f6 0.7")
TARGET_DB = 88.0
BAD = re.compile(r"error|couldn't|failed|DSP loop|multiply defined|stack overflow|bad ", re.I)


def harness():
    def m(t):
        return t.replace(";", " \\;")
    return "\n".join([
        "#N canvas 0 50 600 400 12;",
        "#X obj 20 20 loadbang;",
        "#X msg 20 50 \\; pd dsp 1;",
        "#X obj 20 80 delay 300;",
        f"#X msg 20 110 \\; {m(QUIET)};",
        "#X obj 20 140 delay 1500;",
        f"#X msg 20 170 \\; dn-phase 1 \\; {m(WILD)};",
        "#X obj 20 200 delay 2900;",
        "#X msg 20 230 \\; pd quit;",
        "#X obj 300 20 r dn-m-master;",
        "#X obj 300 50 print M;",
        "#X obj 300 80 r dn-phase;",
        "#X obj 300 110 print PHASE;",
        "#X connect 0 0 1 0;", "#X connect 0 0 2 0;", "#X connect 2 0 3 0;",
        "#X connect 0 0 4 0;", "#X connect 4 0 5 0;", "#X connect 0 0 6 0;",
        "#X connect 6 0 7 0;", "#X connect 8 0 9 0;", "#X connect 10 0 11 0;",
    ]) + "\n"


def run(patch_file, h_file):
    try:
        out = subprocess.run(
            ["pd", "-nogui", "-nosound", "-stderr", "-path", str(LIB),
             "-open", str(MASTER), "-open", str(patch_file), "-open", str(h_file)],
            capture_output=True, text=True, timeout=12).stderr
    except subprocess.TimeoutExpired:
        return None
    a, b, errs, phase = [], [], [], 0
    for line in out.splitlines():
        if "priority" in line or not line.strip():
            continue
        if line.startswith("PHASE"):
            phase = 1
        elif line.startswith("M:"):
            v = line.split(":", 1)[1].strip()
            if v.lower() in ("nan", "inf", "-nan", "-inf"):
                errs.append("nan")
                continue
            (b if phase else a).append(float(v))
        elif BAD.search(line):
            errs.append(line.strip())
    return a[3:], b[3:], errs


def measure(seed, makeup, work, h_file):
    patch, meta = gen_algo.generate(seed, makeup)
    d = work / f"s{seed}"
    d.mkdir(parents=True, exist_ok=True)
    (d / "main.pd").write_text(patch.render())
    r = run(d / "main.pd", h_file)
    return r, meta, d


def db_mean(xs):
    xs = [x for x in xs if x > 0]
    if not xs:
        return 0.0
    return 20 * math.log10(sum(10 ** (x / 20) for x in xs) / len(xs))


def evaluate(seed, work, h_file):
    r, meta, _ = measure(seed, 1.0, work, h_file)
    if r is None:
        return seed, None, "timeout"
    a, b, errs = r
    if errs:
        return seed, None, "error: " + errs[0][:80]
    if not a or not b or max(a) < 50 or max(b) < 50:
        return seed, None, f"mudo ({max(a or [0]):.0f}/{max(b or [0]):.0f} dB)"
    gain = min(60.0, max(0.25, 10 ** ((TARGET_DB - db_mean(a)) / 20)))
    r2, meta, d = measure(seed, gain, work, h_file)
    if r2 is None:
        return seed, None, "timeout 2"
    a2, b2, errs2 = r2
    if errs2:
        return seed, None, "error 2: " + errs2[0][:80]
    la, lb = db_mean(a2), db_mean(b2)
    if not (74 <= la <= 100) or lb < 60:
        return seed, None, f"nivel fuera de rango ({la:.0f}/{lb:.0f} dB)"
    meta["gain"] = gain
    meta["levels"] = (round(la, 1), round(lb, 1))
    return seed, (d / "main.pd", meta), "ok"


def roman(n):
    v = [(1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"), (50, "L"),
         (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")]
    s = ""
    for k, r in v:
        while n >= k:
            s += r
            n -= k
    return s


def from_roman(s):
    vals = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}
    tot = 0
    for i, ch in enumerate(s):
        v = vals.get(ch, 0)
        tot += -v if i + 1 < len(s) and vals.get(s[i + 1], 0) > v else v
    return tot


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("count", type=int, help="cuántos parches nuevos aceptar")
    ap.add_argument("--seed", type=int, default=1000)
    ap.add_argument("--jobs", type=int, default=4)
    args = ap.parse_args()

    existing = [from_roman(p.name) for p in BANK.iterdir() if p.is_dir()]
    next_n = max(existing, default=0) + 1
    work = Path(tempfile.mkdtemp(prefix="dn-farm-"))
    h_file = work / "harness.pd"
    h_file.write_text(harness())

    # Candidatos con firma estructural única: ni repetidos entre sí ni con el banco existente
    # (las firmas del banco se regeneran desde la seed guardada en cada faders.txt).
    sigs, seeds, seed = set(), [], args.seed
    used_seeds = set()
    for f in BANK.glob("*/faders.txt"):
        for line in f.read_text().splitlines():
            if line.startswith("seed:"):
                old = int(line[5:])
                used_seeds.add(old)
                sigs.add(gen_algo.generate(old)[1]["signature"])
    print(f"banco actual: {len(used_seeds)} generados, siguiente número {roman(next_n)}", flush=True)
    while len(seeds) < args.count * 2:
        _, meta = gen_algo.generate(seed)
        if seed not in used_seeds and meta["signature"] not in sigs:
            sigs.add(meta["signature"])
            seeds.append(seed)
        seed += 1

    accepted, reasons = 0, {}
    with ThreadPoolExecutor(args.jobs) as ex:
        futs = {ex.submit(evaluate, s, work, h_file): s for s in seeds}
        for f in as_completed(futs):
            s, res, why = f.result()
            key = why.split(":")[0].split(" (")[0]
            reasons[key] = reasons.get(key, 0) + 1
            if res and accepted < args.count:
                src, meta = res
                name = roman(next_n)
                next_n += 1
                accepted += 1
                d = BANK / name
                d.mkdir(parents=True, exist_ok=True)
                shutil.copy(src, d / "main.pd")
                lines = [f"cat: {meta['cat']}", f"desc: {meta['desc']}", f"seed: {s}",
                         f"gain: {meta['gain']:.3g}"] + [f"{l} {v}" for l, v in meta["faders"]]
                (d / "faders.txt").write_text("\n".join(lines) + "\n")
                print(f"[{accepted}/{args.count}] {name:<12} {meta['cat']:<10} {meta['levels']} {meta['desc']}", flush=True)
            if accepted >= args.count:
                for other in futs:
                    other.cancel()
                break
    shutil.rmtree(work, ignore_errors=True)
    print("resumen:", reasons)


if __name__ == "__main__":
    main()
