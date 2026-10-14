#!/usr/bin/env python3
"""Generador estructural de parches para el banco de DroidNoise.

Cada parche = fuentes + cadena de procesos + topología (serie / loop global / paralelo).
Cada módulo expone parámetros; 5 van a faders (más VOL), el resto queda fijo, y los gestos
(beta, gamma, alpha, giro, luz, sacudón) modulan parámetros al azar. Salida por el contrato del
banco (ver gen_bank.py). La validación y el ajuste de nivel los hace tools/validate_bank.py.

Uso (lo llama validate_bank.py): generate(seed, makeup) -> (Patch, meta)
"""
import random
from dataclasses import dataclass, field

import gen_bank as gb

B = gb.B


@dataclass
class Param:
    label: str
    kind: str                 # "sig" (inlet de señal) o "float" (inlet de control)
    targets: list             # [(obj, inlet)]
    lo: float
    hi: float
    curve: int                # 0 lineal, 1 exponencial
    meter_src: int            # objeto cuya señal mide el fader
    default: float = 0.5      # normalizado 0..1
    gestures: list = field(default_factory=list)
    burst: float = 0.0


class Ctx:
    def __init__(self, b, rnd):
        self.b = b
        self.rnd = rnd
        self.params: list[Param] = []
        self.k = 0            # contador para nombres $0-xxx únicos por módulo
        self.has_gran = False
        self.tags = []
        self.y = 300

    def uid(self):
        self.k += 1
        return self.k

    def P(self, *a, **kw):
        p = Param(*a, **kw)
        self.params.append(p)
        return p

    def row(self, h=40):
        self.y += h
        return self.y


# ------------------------------------------------------------------ fuentes

def src_noise(c):
    b = c.b; y = c.row()
    nz = b.o(20, y, "noise~")
    lp = b.o(20, y + 30, "lop~ 3000")
    b.c(nz, lp)
    c.P("TONE", "float", [(lp, 1)], 200, 12000, 1, lp, c.rnd.uniform(.3, .8))
    c.tags.append("noise")
    return lp


def src_mic(c):
    b = c.b; y = c.row()
    m = b.mic(20, y)
    g = b.o(20, y + 60, "*~")
    b.c(m, g)
    sc = b.o(20, y + 90, "softclip")
    b.c(g, sc)
    c.P("MIC", "sig", [(g, 1)], 0, 8, 0, sc, c.rnd.uniform(.2, .5))
    c.tags.append("mic")
    return sc


def src_fm(c):
    b = c.b; y = c.row(80); k = c.uid()
    oa = b.o(20, y + 60, "osc~"); ob = b.o(200, y + 60, "osc~")
    rb = b.o(20, y, f"delread~ $0-fb{k} 0"); ra = b.o(200, y, f"delread~ $0-fa{k} 0")
    ia = b.o(20, y + 30, "*~"); ib = b.o(200, y + 30, "*~")
    rat = b.o(380, y + 30, "*~")
    b.c(oa, b.o(100, y + 90, f"delwrite~ $0-fa{k} 50")); b.c(ob, b.o(280, y + 90, f"delwrite~ $0-fb{k} 50"))
    b.c(rb, ia, 0, 0); b.c(ra, ib, 0, 0)
    b.c(ia, oa); b.c(rat, ob); b.c(ib, ob)
    ring = b.o(20, y + 120, "*~")
    b.c(oa, ring, 0, 0); b.c(ob, ring, 0, 1)
    mix = b.o(20, y + 150, "*~ 1.5")
    b.c(ring, mix); b.c(oa, mix)
    sc = b.o(20, y + 180, "softclip")
    b.c(mix, sc)
    base = c.P("BASE", "sig", [(oa, 0), (rat, 0)], 30, 900, 1, sc, c.rnd.uniform(.2, .8))
    c.P("FM", "sig", [(ia, 1), (ib, 1)], 20, 3000, 1, sc, c.rnd.uniform(.2, .7))
    c.P("RAT", "sig", [(rat, 1)], 0.5, 3.7, 0, sc, c.rnd.uniform(.1, .9))
    c.tags.append("fm")
    return sc


def src_saw(c):
    b = c.b; y = c.row(80); k = c.uid()
    bf = b.o(20, y, "*~ 1")
    dt = b.o(200, y, "*~ 1")
    out = b.o(20, y + 150, "*~ 0.33")
    for i, sgn in enumerate((0, 1, -1)):
        x = 20 + i * 120
        d = b.o(x, y + 30, f"*~ {sgn}")
        b.c(dt, d)
        one = b.o(x, y + 60, "+~ 1")
        b.c(d, one)
        f = b.mul(bf, one, x, y + 90)
        ph = b.o(x, y + 120, "phasor~")
        b.c(f, ph)
        b.c(b.chain(ph, b.o(x, y + 130, "*~ 2"), b.o(x, y + 140, "-~ 1")), out)
    c.P("BASE", "sig", [(bf, 0)], 25, 400, 1, out, c.rnd.uniform(.1, .6))
    c.P("DTUN", "sig", [(dt, 0)], 0, 0.05, 0, out, c.rnd.uniform(.1, .7))
    c.tags.append("saw")
    return out


def src_square(c):
    b = c.b; y = c.row(60)
    o1 = b.o(20, y, "osc~"); o2 = b.o(200, y, "osc~")
    det = b.o(200, y - 20, f"*~ {c.rnd.choice((1.003, 1.5, 0.997, 2.01))}")
    b.c(det, o2)
    out = b.o(20, y + 90, "*~ 0.5")
    for o in (o1, o2):
        b.c(b.chain(o, b.o(20, y + 30, "*~ 1000"), b.o(20, y + 60, "clip~ -1 1")), out)
    c.P("BASE", "sig", [(o1, 0), (det, 0)], 20, 600, 1, out, c.rnd.uniform(.1, .6))
    c.tags.append("square")
    return out


def src_sines(c):
    b = c.b; y = c.row(60)
    bf = b.o(20, y, "*~ 1")
    out = b.o(20, y + 60, "*~ 0.3")
    for i, r in enumerate(c.rnd.sample((1, 1.5, 2.01, 3.03, 4.7, 0.5, 1.33), 4)):
        x = 20 + i * 90
        m = b.o(x, y + 20, f"*~ {r}")
        b.c(bf, m)
        o = b.o(x, y + 40, "osc~")
        b.c(m, o); b.c(o, out)
    c.P("BASE", "sig", [(bf, 0)], 30, 800, 1, out, c.rnd.uniform(.1, .7))
    c.tags.append("sines")
    return out


def src_clicks(c):
    b = c.b; y = c.row(60)
    met = b.o(20, y, "metro 100")
    on = b.m(120, y - 20, "1")
    b.c(b.lb, on); b.c(on, met)
    rnd = b.o(20, y + 20, f"random {c.rnd.randint(20, 120)}")
    b.c(met, rnd)
    msg = b.m(20, y + 40, "1, 0 $1")
    b.c(rnd, msg)
    env = b.o(20, y + 60, "vline~")
    b.c(msg, env)
    nz = b.o(120, y + 60, "noise~")
    out = b.mul(nz, env, 20, y + 80)
    c.P("RATE", "float", [(met, 1)], 600, 20, 1, out, c.rnd.uniform(.2, .8))
    c.tags.append("clicks")
    return out


SOURCES = [src_noise, src_fm, src_saw, src_square, src_sines, src_clicks]


# ---------------------------------------------------------------- procesos

def p_drive(c, x):
    b = c.b; y = c.row()
    m = b.o(20, y, "*~"); b.c(x, m)
    sc = b.o(20, y + 30, "softclip"); b.c(m, sc)
    c.P("DRV", "sig", [(m, 1)], 1, 60, 1, sc, c.rnd.uniform(.2, .7))
    c.tags.append("drive")
    return sc


def p_fold(c, x):
    b = c.b; y = c.row(60)
    m = b.o(20, y, "*~"); b.c(x, m)
    t = b.chain(m, b.o(20, y + 10, "*~ 0.25"), b.o(20, y + 20, "+~ 0.25"), b.o(20, y + 30, "wrap~"),
                b.o(20, y + 40, "*~ 4"), b.o(20, y + 50, "-~ 2"), b.o(20, y + 60, "abs~"), b.o(20, y + 70, "-~ 1"))
    c.P("FOLD", "sig", [(m, 1)], 1, 10, 1, t, c.rnd.uniform(.1, .6))
    c.tags.append("fold")
    return t


def p_crush(c, x):
    b = c.b; y = c.row()
    ph = b.o(200, y, "phasor~")
    sh = b.o(20, y + 30, "samphold~")
    b.c(x, sh, 0, 0); b.c(ph, sh, 0, 1)
    c.P("CRSH", "sig", [(ph, 0)], 22000, 300, 1, sh, c.rnd.uniform(.2, .7))
    c.tags.append("crush")
    return sh


def p_quant(c, x):
    b = c.b; y = c.row(60)
    lv = b.o(200, y, "*~ 1")
    k1 = b.mul(x, lv, 20, y)
    h = b.o(20, y + 20, "+~ 0.5"); b.c(k1, h)
    w = b.o(120, y + 40, "wrap~"); b.c(h, w)
    fl = b.o(20, y + 60, "-~"); b.c(h, fl, 0, 0); b.c(w, fl, 0, 1)
    q = b.o(20, y + 80, "/~"); b.c(fl, q, 0, 0); b.c(lv, q, 0, 1)
    c.P("BITS", "sig", [(lv, 0)], 128, 2, 1, q, c.rnd.uniform(.2, .7))
    c.tags.append("crush")
    return q


def p_comb(c, x):
    b = c.b; y = c.row(60); k = c.uid()
    w = b.o(20, y + 60, f"delwrite~ $0-c{k} 100")
    r = b.o(200, y, f"delread4~ $0-c{k}")
    sc = b.o(200, y + 20, "softclip"); b.c(r, sc)
    fb = b.o(200, y + 40, "*~"); b.c(sc, fb)
    b.c(x, w); b.c(fb, w)
    out = b.o(20, y + 80, "+~"); b.c(x, out, 0, 0); b.c(r, out, 0, 1)
    c.P("CTIM", "sig", [(r, 0)], 0.5, 30, 1, out, c.rnd.uniform(.1, .9))
    c.P("COMB", "sig", [(fb, 1)], 0, 0.97, 0, out, c.rnd.uniform(.3, .8))
    c.tags.append("comb")
    return out


def p_res(c, x):
    b = c.b; y = c.row(60)
    cf = b.o(200, y, "*~ 1")
    out = b.o(20, y + 60, "*~ 3")
    vcfs = []
    for i, r in enumerate(c.rnd.sample((1, 1.47, 2.13, 2.9, 0.71, 3.6), 3)):
        xx = 20 + i * 100
        m = b.o(xx, y + 20, f"*~ {r}"); b.c(cf, m)
        v = b.o(xx, y + 40, "vcf~ 10")
        b.c(x, v, 0, 0); b.c(m, v, 0, 1); b.c(v, out)
        vcfs.append(v)
    sc = b.o(20, y + 80, "softclip"); b.c(out, sc)
    c.P("RES", "sig", [(cf, 0)], 100, 3500, 1, sc, c.rnd.uniform(.2, .8))
    c.P("Q", "float", [(v, 2) for v in vcfs], 4, 40, 1, sc, c.rnd.uniform(.2, .8))
    c.tags.append("res")
    return sc


def p_low(c, x):
    b = c.b; y = c.row()
    v = b.o(20, y, "vcf~ 2")
    b.c(x, v, 0, 0)
    out = b.o(20, y + 30, "*~ 1")
    b.c(v, out, 1)
    c.P("LOW", "sig", [(v, 1)], 150, 9000, 1, out, c.rnd.uniform(.3, .8))
    c.tags.append("low")
    return out


def p_delay(c, x):
    b = c.b; y = c.row(60); k = c.uid()
    w = b.o(20, y + 60, f"delwrite~ $0-d{k} 2000")
    r = b.o(200, y, f"delread4~ $0-d{k}")
    sc = b.o(200, y + 20, "softclip"); b.c(r, sc)
    fb = b.o(200, y + 40, "*~"); b.c(sc, fb)
    b.c(x, w); b.c(fb, w)
    out = b.o(20, y + 80, "+~"); b.c(x, out, 0, 0); b.c(r, out, 0, 1)
    c.P("TIME", "sig", [(r, 0)], 20, 1500, 1, out, c.rnd.uniform(.1, .9))
    c.P("FDBK", "sig", [(fb, 1)], 0, 1.15, 0, out, c.rnd.uniform(.3, .8))
    c.tags.append("delay")
    return out


def p_ring(c, x):
    b = c.b; y = c.row(60)
    o = b.o(200, y, "osc~")
    wet = b.mul(x, o, 20, y + 20)
    dif = b.o(20, y + 40, "-~"); b.c(wet, dif, 0, 0); b.c(x, dif, 0, 1)
    amt = b.o(20, y + 60, "*~"); b.c(dif, amt)
    out = b.o(20, y + 80, "+~"); b.c(amt, out, 0, 0); b.c(x, out, 0, 1)
    c.P("RFRQ", "sig", [(o, 0)], 20, 2000, 1, out, c.rnd.uniform(.1, .9))
    c.P("RING", "sig", [(amt, 1)], 0, 1, 0, out, c.rnd.uniform(.4, .9))
    c.tags.append("ring")
    return out


def p_chop(c, x):
    b = c.b; y = c.row(80)
    met = b.o(200, y, "metro 100")
    on = b.m(300, y - 20, "1"); b.c(b.lb, on); b.c(on, met)
    r = b.o(200, y + 20, "random 100"); b.c(met, r)
    mo = b.o(200, y + 40, f"moses {c.rnd.randint(20, 60)}"); b.c(r, mo)
    z = b.m(200, y + 60, "0 3"); o = b.m(260, y + 60, "1 3")
    b.c(mo, z, 0); b.c(mo, o, 1)
    g = b.o(200, y + 80, "line~"); b.c(z, g); b.c(o, g)
    out = b.mul(x, g, 20, y + 80)
    c.P("CHOP", "float", [(met, 1)], 600, 30, 1, out, c.rnd.uniform(.2, .8))
    c.tags.append("chop")
    return out


def p_trem(c, x):
    b = c.b; y = c.row()
    o = b.o(200, y, "osc~")
    a = b.chain(o, b.o(200, y + 10, "*~ 0.5"), b.o(200, y + 20, "+~ 0.5"))
    out = b.mul(x, a, 20, y + 30)
    c.P("TREM", "sig", [(o, 0)], 0.5, 40, 1, out, c.rnd.uniform(.2, .8))
    c.tags.append("trem")
    return out


def p_gran(c, x):
    if c.has_gran:
        return p_drive(c, x)
    c.has_gran = True
    b = c.b; y = c.row(360)
    buf = b.o(20, y, "delwrite~ $0-grain 5000")
    b.c(x, buf)
    dens = b.o(600, y, "* 1")
    iv = gb.interval(b, dens, 600, y + 30)
    ratio = b.o(700, y, "* 1")
    cl, _ = gb.grain_cloud(b, 300, y + 60, 12, (iv, 0), (ratio, 0), 300, 40, 1800, 2100, 0.5)
    g = b.o(300, y + 360, "*~ 2"); b.c(cl, g)
    dry = b.o(20, y + 360, "*~ 0.3"); b.c(x, dry)
    out = b.o(20, y + 390, "+~"); b.c(g, out, 0, 0); b.c(dry, out, 0, 1)
    # dens y ratio se alimentan por su inlet izquierdo (float caliente)
    c.P("DENS", "float", [(dens, 0)], 2, 60, 1, out, c.rnd.uniform(.2, .7))
    c.P("PTCH", "float", [(ratio, 0)], 0.25, 4, 1, out, c.rnd.uniform(.2, .8))
    c.tags.append("gran")
    return out


def p_net(c, x):
    """Red de feedback 2x2: dos delays cruzados con bandpass y saturación."""
    b = c.b; y = c.row(140); k = c.uid()
    t = b.o(400, y, "*~ 1")
    cc = b.o(500, y, "*~ 1")
    gn = b.o(600, y, "*~ 1")
    w1 = b.o(20, y + 120, f"delwrite~ $0-na{k} 200")
    w2 = b.o(200, y + 120, f"delwrite~ $0-nb{k} 200")
    b.c(x, w1); b.c(x, w2)
    outs = []
    for i, (w, rt, rc) in enumerate(((w1, 1, 1), (w2, 1.37, 1.6))):
        xx = 20 + i * 180
        tt = b.o(xx, y, f"*~ {rt}"); b.c(t, tt)
        rd = b.o(xx, y + 20, f"delread4~ $0-n{'ab'[i]}{k}"); b.c(tt, rd)
        cm = b.o(xx + 80, y + 20, f"*~ {rc}"); b.c(cc, cm)
        bp = b.o(xx, y + 40, "vcf~ 8"); b.c(rd, bp, 0, 0); b.c(cm, bp, 0, 1)
        dr = b.o(xx, y + 60, "*~ 3"); b.c(bp, dr)
        sc = b.o(xx, y + 70, "softclip"); b.c(dr, sc)
        g = b.mul(sc, gn, xx, y + 80)
        hh = b.o(xx, y + 100, "hip~ 30"); b.c(g, hh)
        outs.append(hh)
    for i, (a, s) in enumerate(((outs[0], 0.7), (outs[1], 0.7))):
        for j, w in enumerate((w1, w2)):
            m = b.o(400 + i * 80 + j * 40, y + 110, f"*~ {s if not (i == 1 and j == 1) else -s}")
            b.c(a, m); b.c(m, w)
    out = b.o(20, y + 150, "+~"); b.c(outs[0], out, 0, 0); b.c(outs[1], out, 0, 1)
    c.P("TIME", "sig", [(t, 0)], 1.5, 40, 1, out, c.rnd.uniform(.1, .9))
    c.P("RES", "sig", [(cc, 0)], 120, 4000, 1, out, c.rnd.uniform(.2, .8))
    c.P("GAIN", "sig", [(gn, 0)], 0.4, 1.4, 0, out, c.rnd.uniform(.4, .8))
    c.tags.append("net")
    return out


PROCS = [p_drive, p_fold, p_crush, p_quant, p_comb, p_res, p_low, p_delay, p_ring, p_chop,
         p_trem, p_gran, p_net]


# -------------------------------------------------------------- gestos

GESTURES = {
    # nombre: (salida que da el float, rango de entrada)
    "beta": None, "gamma": None, "alpha": None, "gyro": None, "light": None,
}


def gesture_sources(b):
    u = b.ori(20, 20)
    rad = b.o(140, 80, "* 0.0174533"); b.c(u, rad, 0)
    sn = b.o(140, 110, "sin"); b.c(rad, sn)
    gy = b.gyro(400, 20)
    lt = b.light(600, 20)
    return {"beta": ((u, 1), -90, 90), "gamma": ((u, 2), -90, 90), "alpha": ((sn, 0), -1, 1),
            "gyro": ((gy, 0), 0, 6), "light": ((lt, 0), 0, 7)}


def wire_param(b, p, ctrl, gsrc, burst_sig, x, y):
    """ctrl: (obj, outlet) float 0..1. Arma map + gestos + sacudón + clip y conecta a los destinos."""
    m = b.o(x, y, f"map 0 1 {p.lo:g} {p.hi:g} {p.curve} 60")
    b.c(ctrl[0], m, ctrl[1])
    lo, hi = min(p.lo, p.hi), max(p.lo, p.hi)
    if p.kind == "sig":
        sig = (m, 0)
        yy = y + 30
        for gname, inv in p.gestures:
            (go, gout), glo, ghi = gsrc[gname]
            a, z = (2, 0.5) if inv else (0.5, 2)
            gm = b.o(x + 120, yy, f"map {glo:g} {ghi:g} {a} {z} 1 200")
            b.c(go, gm, gout)
            mm = b.o(x, yy + 20, "*~"); b.c(sig[0], mm, sig[1], 0); b.c(gm, mm, 0, 1)
            sig = (mm, 0); yy += 40
        if p.burst and burst_sig is not None:
            k = b.o(x + 120, yy, f"*~ {p.burst:g}"); b.c(burst_sig, k)
            one = b.o(x + 120, yy + 20, "+~ 1"); b.c(k, one)
            mm = b.o(x, yy + 20, "*~"); b.c(sig[0], mm, sig[1], 0); b.c(one, mm, 0, 1)
            sig = (mm, 0); yy += 40
        if p.gestures or p.burst:
            cl = b.o(x, yy, f"clip~ {lo:g} {hi:g}"); b.c(sig[0], cl, sig[1]); sig = (cl, 0)
        for o, inl in p.targets:
            b.c(sig[0], o, sig[1], inl)
    else:
        val = (m, 1)
        yy = y + 30
        for gname, inv in p.gestures:
            (go, gout), glo, ghi = gsrc[gname]
            a, z = (2, 0.5) if inv else (0.5, 2)
            gm = b.o(x + 120, yy, f"map {glo:g} {ghi:g} {a} {z} 1 0")
            b.c(go, gm, gout)
            prod = gb.fmul(b, val[0], gm, x, yy + 40, ao=val[1], boo=1)
            val = (prod, 0); yy += 70
        cl = b.o(x, yy, f"clip {lo:g} {hi:g}"); b.c(val[0], cl, val[1])
        for o, inl in p.targets:
            b.c(cl, o, 0, inl)


# --------------------------------------------------------------- parche

# Categoría por puntaje: cada módulo suma a una o más categorías; gana la de más puntos
# (empate: el orden de la lista). Así no se va todo a "feedback" por tener un delay.
CATEGORY_WEIGHTS = {
    "feedback": {"net": 3, "delay": 2, "LOOP": 1},
    "glitch": {"chop": 3, "crush": 2, "quant": 2},
    "granular": {"gran": 4},
    "resonante": {"res": 2, "comb": 2},
    "fm": {"fm": 2, "ring": 2},
    "wall": {"fold": 2, "drive": 1, "noise": 1},
    "drone": {"saw": 2, "sines": 2, "square": 1, "low": 1, "trem": 1},
    "pulso": {"clicks": 2, "chop": 1, "trem": 1},
}


def category(tags):
    score = {c: sum(w.get(t, 0) for t in tags) for c, w in CATEGORY_WEIGHTS.items()}
    best = max(score.values())
    return next(c for c in CATEGORY_WEIGHTS if score[c] == best) if best > 0 else "ruido"


def generate(seed, makeup=1.0):
    rnd = random.Random(seed)
    b = B(1800, 2400)
    c = Ctx(b, rnd)
    gsrc = gesture_sources(b)
    sk = b.shake(800, 20)
    bm = b.m(1000, 120, f"1 5, 0 {rnd.randint(500, 2500)} 5")
    b.c(sk, bm)
    burst = b.o(1000, 150, "vline~"); b.c(bm, burst)

    # fuentes: 1–2 sin mic + mic opcional
    srcs = rnd.sample(SOURCES, rnd.choice((1, 1, 2)))
    sig_srcs = [s(c) for s in srcs]
    if rnd.random() < 0.55:
        sig_srcs.append(src_mic(c))
    x = b.o(20, c.row(), "*~ 1")
    for s in sig_srcs:
        b.c(s, x)

    topo = rnd.choice(("serie", "serie", "loop", "paralelo"))
    n = rnd.choice((2, 3, 3, 4))
    chain = rnd.sample(PROCS, n)
    loop_w = None
    if topo == "loop":
        lr = b.o(300, c.row(), "delread4~ $0-loop")
        lsc = b.o(300, c.row(20), "softclip"); b.c(lr, lsc)
        lfb = b.o(300, c.row(20), "*~"); b.c(lsc, lfb)
        lh = b.o(300, c.row(20), "hip~ 30"); b.c(lfb, lh)
        b.c(lh, x)
        c.P("LTIM", "sig", [(lr, 0)], 5, 800, 1, lh, rnd.uniform(.1, .9))
        c.P("LOOP", "sig", [(lfb, 1)], 0, 1.2, 0, lh, rnd.uniform(.3, .8))
        c.tags.append("LOOP")
    if topo == "paralelo":
        cut = max(1, n // 2)
        a = x
        for p in chain[:cut]:
            a = p(c, a)
        bb = x
        for p in chain[cut:]:
            bb = p(c, bb)
        y = b.o(20, c.row(), "+~"); b.c(a, y, 0, 0); b.c(bb, y, 0, 1)
    else:
        y = x
        for p in chain:
            y = p(c, y)
    if topo == "loop":
        lw = b.o(300, c.row(), "delwrite~ $0-loop 1000"); b.c(y, lw)

    # faders: VOL + 5 parámetros (MIC primero si hay)
    params = c.params
    mic = [p for p in params if p.label == "MIC"]
    rest = [p for p in params if p.label != "MIC"]
    rnd.shuffle(rest)
    fad = (mic + rest)[:5]
    # etiquetas únicas
    seen = {}
    for p in fad:
        if p.label in seen:
            seen[p.label] += 1
            p.label = f"{p.label[:3]}{seen[p.label]}"
        else:
            seen[p.label] = 1
    # gestos: cada uno a 1–2 parámetros al azar; sacudón a uno de señal
    for gname in ("beta", "gamma", "alpha", "gyro", "light"):
        if rnd.random() < 0.85:
            for p in rnd.sample(params, min(len(params), rnd.choice((1, 1, 2)))):
                if len(p.gestures) < 2:
                    p.gestures.append((gname, rnd.random() < 0.3))
    sigp = [p for p in params if p.kind == "sig"]
    if sigp:
        rnd.choice(sigp).burst = rnd.choice((1, 2, 3))

    fv = b.fader(1, 0.8, 1600, 60)
    yy = 60
    for i, p in enumerate(fad):
        f = b.fader(i + 2, round(p.default, 2), 1600, 90 + i * 30)
        wire_param(b, p, (f, 1), gsrc, burst, 1000 + i * 260, 1400 + 0)
        b.meter(p.meter_src, i + 2, 1600, 300 + i * 30)
    for j, p in enumerate(q for q in params if q not in fad):
        d = b.m(1000 + j * 260, 1900, f"{p.default:.3f}")
        b.c(b.lb, d)
        wire_param(b, p, (d, 0), gsrc, burst, 1000 + j * 260, 1930)

    # salida: DC fuera, makeup, saturación, volumen, estéreo
    h = b.o(20, c.row(), "hip~ 25"); b.c(y, h)
    mk = b.o(20, c.row(20), f"*~ {makeup:.4g}"); b.c(h, mk)
    sc = b.o(20, c.row(20), "softclip"); b.c(mk, sc)
    vol = b.sq(fv, 200, c.y)
    v = b.mul(sc, vol, 20, c.row(20))
    b.meter(v, 1, 120, c.y)
    r = b.width(v, 200, c.row(20), ms=rnd.choice((5, 7, 11, 13)))
    b.out(v, r, 20, c.row(60))

    labels = ["VOL"] + [p.label for p in fad]
    defaults = [0.8] + [round(p.default, 2) for p in fad]
    while len(labels) < 6:
        labels.append(f"F{len(labels) + 1}"); defaults.append(0.5)
    meta = {
        "cat": category(c.tags),
        "desc": f"{topo}: {' + '.join(s.__name__[4:] for s in srcs)}{' + mic' if mic else ''} -> "
                + " > ".join(p.__name__[2:] for p in chain),
        "faders": list(zip(labels, defaults)),
        "signature": (topo, tuple(sorted(s.__name__ for s in srcs)), bool(mic), tuple(p.__name__ for p in chain)),
    }
    return b.p, meta
