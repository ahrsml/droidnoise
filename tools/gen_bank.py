#!/usr/bin/env python3
"""Genera el banco de parches de DroidNoise: pd/droidnoise/{lib,bank/*} y el master de la app.

Contrato de un parche del banco (bank/<nombre>/):
  main.pd     recibe dn-f1..dn-f6 (faders 0..1, vía [fader N default]), ori/gyro/light/acc vía
              [held ...]/[r acc], y saca el audio por [out~ $0] (fade de entrada/salida, gate de
              proximidad, throw~ al master). Medidores por fader: [meter~ N] -> dn-m-N.
              Todo nombre interno de delay/tabla lleva $0: durante el crossfade suenan dos parches.
  faders.txt  "cat: <categoría>", "desc: <texto>" y 6 líneas "ETIQUETA default".

El master (app/src/main/assets/master.pd) vive siempre: catch~ dn-out-L/R -> dac~ y graba con
writesf~ (receiver dn-rec). Solo objetos de Pd 0.51 (libpd 1.2.1-rc6).

Uso: python3 tools/gen_bank.py
"""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "pd" / "droidnoise"
ASSETS = ROOT / "app" / "src" / "main" / "assets"

_spec = importlib.util.spec_from_file_location("gen_droidnoise", ROOT / "tools" / "gen_droidnoise.py")
g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(g)
Patch = g.Patch


# ======================================================================= librería

def fader():
    """[fader N default]: dn-fN -> outlet~ izquierdo (line~ 60 ms), outlet derecho float."""
    p = Patch(400, 260)
    r = p.obj(20, 20, "r dn-f$1")
    lb = p.obj(160, 20, "loadbang")
    d = p.obj(160, 50, "f $2")
    t = p.obj(20, 90, "t f f")
    pk = p.obj(20, 130, "pack f 60")
    ln = p.obj(20, 160, "line~")
    o = p.obj(20, 210, "outlet~")
    of = p.obj(200, 210, "outlet")
    p.c(lb, d); p.c(d, t); p.c(r, t)
    p.c(t, of, 1); p.c(t, pk, 0); p.c(pk, ln); p.c(ln, o)
    return p


def held():
    """[held ori|gyro|light]: el receiver, cortado mientras HOLD está activo."""
    p = Patch(300, 200)
    r = p.obj(20, 20, "r $1")
    h = p.obj(120, 20, "r hold")
    n = p.obj(120, 50, "== 0")
    sp = p.obj(20, 80, "spigot 1")
    o = p.obj(20, 130, "outlet")
    p.c(h, n); p.c(n, sp, 0, 1); p.c(r, sp); p.c(sp, o)
    return p


def meter():
    """[meter~ N]: nivel en dB (100 = 0 dBFS) a dn-m-N cada ~21 ms."""
    p = Patch(300, 160)
    i = p.obj(20, 20, "inlet~")
    e = p.obj(20, 50, "env~ 2048")
    s = p.obj(20, 80, "s dn-m-$1")
    p.c(i, e); p.c(e, s)
    return p


def out():
    """[out~ $0]: fade (entra solo al cargar; la app manda <$0>-fade 0 para sacarlo), gate de
    proximidad y throw~ al master."""
    p = Patch(500, 320)
    il = p.obj(20, 20, "inlet~")
    ir = p.obj(200, 20, "inlet~")
    lb = p.obj(320, 20, "loadbang")
    one = p.msg(320, 50, "1")
    rf = p.obj(320, 80, "r $1-fade")
    pk = p.obj(320, 110, "pack f 1500")
    fade = p.obj(320, 140, "line~")
    rp = p.obj(420, 20, "r proxnear")
    eq = p.obj(420, 50, "== 0")
    gm = p.msg(420, 80, "$1 20")
    gate = p.obj(420, 110, "line~")
    g1 = p.msg(470, 50, "1")
    amp = p.obj(320, 180, "*~")
    ml = p.obj(20, 220, "*~")
    mr = p.obj(200, 220, "*~")
    tl = p.obj(20, 270, "throw~ dn-out-L")
    tr = p.obj(200, 270, "throw~ dn-out-R")
    p.c(lb, one); p.c(one, pk); p.c(rf, pk); p.c(pk, fade)
    p.c(rp, eq); p.c(eq, gm); p.c(lb, g1); p.c(g1, gm); p.c(gm, gate)
    p.c(fade, amp, 0, 0); p.c(gate, amp, 0, 1)
    p.c(il, ml, 0, 0); p.c(amp, ml, 0, 1); p.c(ir, mr, 0, 0); p.c(amp, mr, 0, 1)
    p.c(ml, tl); p.c(mr, tr)
    return p


def grain():
    """Voz de grano sobre un delwrite~: lista d0_ms dur_ms ratio amp; $2 = $0 del parche."""
    p = g.grain()
    p.lines = [l.replace("delread4~ dn-grain", "delread4~ \\$2-grain") for l in p.lines]
    return p


def grain_tab():
    """Voz de grano sobre una tabla: lista inicio fin dur_ms amp (en muestras); $2 = $0 del parche."""
    p = Patch(600, 420)
    i = p.obj(20, 20, "inlet")
    t = p.obj(20, 50, "t l l")
    u = p.obj(250, 80, "unpack f f f f")
    amp = p.obj(500, 300, "*~")
    pk = p.obj(250, 200, "pack f f f")
    mr = p.msg(250, 230, "$1, $2 $3")
    vl = p.obj(250, 260, "vline~")
    rd = p.obj(250, 290, "tabread4~ $2-smp")
    me = p.msg(20, 110, "0, 1 $3")
    ve = p.obj(20, 140, "vline~")
    cs = p.obj(20, 170, "cos~")
    sc = p.obj(20, 200, "*~ -0.5")
    of = p.obj(20, 230, "+~ 0.5")
    env = p.obj(20, 300, "*~")
    o = p.obj(20, 380, "outlet~")
    p.c(i, t); p.c(t, u, 1)
    # unpack: amp, dur, fin, inicio (de derecha a izquierda)
    p.c(u, amp, 3, 1); p.c(u, pk, 2, 2); p.c(u, pk, 1, 1); p.c(u, pk, 0, 0)
    p.c(pk, mr); p.c(mr, vl); p.c(vl, rd)
    p.c(t, me, 0); p.c(me, ve); p.c(ve, cs); p.c(cs, sc); p.c(sc, of)
    p.c(of, env, 0, 0); p.c(rd, env, 0, 1); p.c(env, amp, 0, 0); p.c(amp, o)
    return p


def master():
    """Siempre cargado: suma los parches, limita, saca al jack y graba (dn-rec)."""
    p = Patch(600, 340)
    cl = p.obj(20, 20, "catch~ dn-out-L")
    cr = p.obj(220, 20, "catch~ dn-out-R")
    kl = p.obj(20, 60, "clip~ -0.98 0.98")
    kr = p.obj(220, 60, "clip~ -0.98 0.98")
    dac = p.obj(20, 120, "dac~ 1 2")
    rr = p.obj(420, 20, "r dn-rec")
    w = p.obj(220, 160, "writesf~ 2")
    e = p.obj(420, 120, "env~ 2048")
    s = p.obj(420, 150, "s dn-m-master")
    p.c(cl, kl); p.c(cr, kr); p.c(kl, dac, 0, 0); p.c(kr, dac, 0, 1)
    p.c(kl, w, 0, 0); p.c(kr, w, 0, 1); p.c(rr, w)
    p.c(kl, e); p.c(e, s)
    p.text(20, 220, "DroidNoise master: dn-out-L/R -> jack + REC (writesf~ via dn-rec)")
    return p


# ================================================================ piezas comunes

class B:
    """Ayudante para armar parches del banco."""

    def __init__(self, w=1600, h=1200):
        self.p = Patch(w, h)
        self.lb = self.p.obj(1400, 20, "loadbang")

    def o(self, x, y, t):
        return self.p.obj(x, y, t)

    def m(self, x, y, t):
        return self.p.msg(x, y, t)

    def c(self, a, b, out=0, inl=0):
        self.p.c(a, b, out, inl)

    def chain(self, *objs):
        for a, b in zip(objs, objs[1:]):
            self.c(a, b)
        return objs[-1]

    def fader(self, n, default, x=1400, y=None):
        return self.o(x, y if y is not None else 60 + n * 30, f"fader {n} {default}")

    def sq(self, sig, x, y):
        """Cuadrado de una señal (curva de volumen)."""
        s = self.o(x, y, "*~")
        self.c(sig, s, 0, 0); self.c(sig, s, 0, 1)
        return s

    def mul(self, a, b, x, y, ao=0, bo=0):
        m = self.o(x, y, "*~")
        self.c(a, m, ao, 0); self.c(b, m, bo, 1)
        return m

    def ori(self, x=20, y=20):
        h = self.o(x, y, "held ori")
        u = self.o(x, y + 30, "unpack f f f")
        self.c(h, u)
        return u  # 0 alpha, 1 beta, 2 gamma

    def gyro(self, x=400, y=20):
        h = self.o(x, y, "held gyro")
        return self.chain(h, self.o(x, y + 30, "mag3"))

    def light(self, x=600, y=20):
        h = self.o(x, y, "held light")
        return self.chain(h, self.o(x, y + 30, "+ 1"), self.o(x, y + 60, "log"))

    def shake(self, x=800, y=20):
        """Sacudón: dispara vibración y devuelve el objeto que emite en cada golpe."""
        sk = self.chain(self.o(x, y, "r acc"), self.o(x, y + 30, "shake 8 2 300"))
        v = self.m(x + 120, y + 60, "vibrate 40")
        self.c(sk, v); self.c(v, self.o(x + 120, y + 90, "s toapp"))
        return sk

    def mic(self, x=20, y=400):
        return self.chain(self.o(x, y, "adc~ 1"), self.o(x, y + 30, "hip~ 80"))

    def meter(self, sig, n, x, y):
        self.c(sig, self.o(x, y, f"meter~ {n}"))

    def out(self, left, right, x, y):
        o = self.o(x, y, "out~ $0")
        self.c(left, o, 0, 0); self.c(right, o, 0, 1)
        return o

    def width(self, sig, x, y, ms=11):
        """Estéreo barato: R = L retrasado unos ms."""
        w = self.o(x, y, f"delwrite~ $0-w 50")
        r = self.o(x, y + 30, f"delread~ $0-w {ms}")
        self.c(sig, w)
        return r


def grain_cloud(b, x, y, voices, rate_ms, ratio, dur_rand, dur_min, pos_rand, pos_min, amp, size=None):
    """Metro -> listas d0 dur ratio amp para [clone grain voices $0]. rate_ms/ratio: (obj, outlet)
    que dan el intervalo y la razón de pitch en float. Devuelve el clone."""
    met = b.o(x, y, "metro 100")
    on = b.m(x, y - 30, "1")
    b.c(b.lb, on); b.c(on, met)
    b.c(rate_ms[0], met, rate_ms[1], 1)
    tm = b.o(x, y + 30, "t b b b b b")
    cnt = b.o(x + 340, y + 60, "f")
    inc = b.o(x + 380, y + 60, "+ 1")
    md = b.o(x + 380, y + 90, f"mod {voices}")
    a = b.m(x + 280, y + 60, str(amp))
    rj = b.o(x + 180, y + 60, "random 100")
    rj1 = b.o(x + 180, y + 90, "- 50")
    rj2 = b.o(x + 180, y + 120, "/ 500")
    rj3 = b.o(x + 180, y + 150, "+ 1")
    rj4 = b.o(x + 180, y + 180, "* 1")
    rd = b.o(x + 90, y + 60, f"random {dur_rand}")
    rd1 = b.o(x + 90, y + 90, f"+ {dur_min}")
    rp = b.o(x, y + 60, f"random {pos_rand}")
    rp1 = b.o(x, y + 90, f"+ {pos_min}")
    pk = b.o(x, y + 220, "pack f f f f")
    lp = b.o(x, y + 250, "list prepend 0")
    cl = b.o(x, y + 280, f"clone grain {voices} $0")
    b.c(met, tm)
    b.c(tm, cnt, 4); b.c(cnt, inc); b.c(inc, md); b.c(md, cnt, 0, 1); b.c(cnt, lp, 0, 1)
    b.c(tm, a, 3); b.c(a, pk, 0, 3)
    b.c(tm, rj, 2); b.chain(rj, rj1, rj2, rj3, rj4); b.c(rj4, pk, 0, 2)
    b.c(ratio[0], rj4, ratio[1], 1)
    if size is None:
        b.c(tm, rd, 1); b.c(rd, rd1); b.c(rd1, pk, 0, 1)
    else:
        sm = b.o(x + 90, y + 120, "* 1")
        b.c(size[0], sm, size[1], 1)
        b.c(tm, rd, 1); b.c(rd, rd1); b.c(rd1, sm); b.c(sm, pk, 0, 1)
    b.c(tm, rp, 0); b.c(rp, rp1); b.c(rp1, pk, 0, 0)
    b.c(pk, lp); b.c(lp, cl)
    return cl, met


def interval(b, dens_obj, x, y, outlet=0):
    """Granos por segundo (float, de un [*] o de la salida 1 de un [map]) -> intervalo en ms."""
    sw = b.o(x, y, "swap 1000")
    dv = b.o(x, y + 30, "/")
    b.c(dens_obj, sw, outlet, 0); b.c(sw, dv, 1, 1); b.c(sw, dv, 0, 0)
    return dv


def fmul(b, a, bo, x, y, ao=1, boo=1):
    """Producto de dos floats (salida derecha de dos [map]) siempre actualizado."""
    t = b.o(x, y, "*")
    tb = b.o(x + 60, y - 30, "t b f")
    b.c(a, t, ao, 0)
    b.c(bo, tb, boo, 0); b.c(tb, t, 1, 1); b.c(tb, t, 0, 0)
    return t


# ==================================================================== parches

def matadero():
    """La máquina v2, adaptada al contrato del banco."""
    p = g.main()
    ren = {"r dn-vol": "r dn-f1", "r dn-mic": "r dn-f2", "r dn-core": "r dn-f3",
           "r dn-chaos": "r dn-f4", "r dn-drone": "r dn-f5", "r dn-grains": "r dn-f6"}
    meters = {"s dn-m-out": "s dn-m-1", "s dn-m-mic": "s dn-m-2", "s dn-m-core": "s dn-m-3",
              "s dn-m-chaos": "s dn-m-4", "s dn-m-drone": "s dn-m-5", "s dn-m-grains": "s dn-m-6"}
    lines = []
    for l in p.lines:
        for a, b_ in ren.items():
            l = l.replace(f" {a};", f" {b_};")
        for a, b_ in meters.items():
            l = l.replace(f" {a};", f" {b_};")
        for n in ("cha", "chb", "d1", "d2", "grain"):
            l = l.replace(f"dn-{n} ", f"\\$0-{n} ").replace(f"dn-{n};", f"\\$0-{n};")
        l = l.replace("clone grain 12;", "clone grain 12 \\$0;")
        l = l.replace("dac~ 1 2;", "out~ \\$0;")
        lines.append(l)
    p.lines = lines
    return p


def muro():
    b = B()
    f = [None] + [b.fader(n, d) for n, d in ((1, .8), (2, .5), (3, .3), (4, .5), (5, .6), (6, .3))]
    u = b.ori(); gy = b.gyro(); lt = b.light(); sk = b.shake()
    # fuente: ruido + mic
    nz = b.o(20, 400, "noise~")
    mic = b.mic(140, 400)
    micg = b.mul(mic, f[6], 140, 470)
    mk = b.o(140, 500, "*~ 6")
    b.c(micg, mk)
    src = b.o(20, 540, "*~ 0.6")
    b.c(nz, src); b.c(mk, src)
    # GRIT: drive exponencial * inclinación (beta) * hinchazón del sacudón
    dg = b.o(300, 400, "map 0 1 1 60 1 80")
    b.c(f[2], dg, 1)
    db = b.o(300, 430, "map -90 90 0.5 2 1 200")
    b.c(u, db, 1)
    dr = b.mul(dg, db, 300, 460)
    sw = b.chain(b.m(800, 140, "1 5, 0 900 5"), b.o(800, 170, "vline~"))
    b.c(sk, b.p.n - 2)  # el msg recién creado
    swk = b.o(800, 200, "*~ 3")
    b.c(sw, swk)
    one = b.o(800, 230, "+~ 1")
    b.c(swk, one)
    drv = b.mul(dr, one, 300, 490)
    d1 = b.mul(src, drv, 20, 580)
    s1 = b.o(20, 610, "softclip")
    b.c(d1, s1)
    b.meter(s1, 2, 120, 610)
    # COMB L/R con realimentación; tiempo por gamma, bamboleo por alpha
    ct = b.o(500, 400, "map -90 90 25 1 1 300")
    b.c(u, ct, 2)
    wob = b.chain(b.o(500, 430, "osc~ 0.11"), b.o(500, 460, "*~ 0.05"), b.o(500, 490, "+~ 1"))
    tl = b.mul(ct, wob, 500, 520)
    trr = b.o(580, 550, "*~ 1.31")
    b.c(tl, trr)
    cfb = b.o(700, 400, "map 0 1 0 0.97 0 100")
    b.c(f[4], cfb, 1)
    combs = []
    for k, (tt, name) in enumerate(((tl, "cl"), (trr, "cr"))):
        x = 20 + k * 300
        w = b.o(x, 700, f"delwrite~ $0-{name} 200")
        r = b.o(x, 650, f"delread4~ $0-{name}")
        b.c(tt, r)
        sc = b.o(x, 680, "softclip")
        fb = b.mul(sc, cfb, x + 100, 680)
        b.c(r, sc)
        b.c(s1, w); b.c(fb, w)
        mix = b.o(x, 740, "+~")
        b.c(s1, mix, 0, 0); b.c(r, mix, 0, 1)
        combs.append(mix)
    b.meter(combs[0], 4, 400, 740)
    # CRSH: sample & hold a tasa decreciente; el giro lo empeora
    cr = b.o(900, 400, "map 0 1 22000 300 1 80")
    b.c(f[3], cr, 1)
    gk = b.o(900, 430, "map 0 6 1 0.25 0 200")
    b.c(gy, gk)
    rate = b.mul(cr, gk, 900, 460)
    ph = b.o(900, 490, "phasor~")
    b.c(rate, ph)
    outs = []
    for k, cm in enumerate(combs):
        x = 20 + k * 300
        sh = b.o(x, 800, "samphold~")
        b.c(cm, sh, 0, 0); b.c(ph, sh, 0, 1)
        # LOW: lowpass resonante (q 2 es estable hasta 12 kHz)
        lp = b.o(x, 840, "vcf~ 2")
        b.c(sh, lp, 0, 0)
        outs.append((sh, lp))
    b.meter(outs[0][0], 3, 120, 800)
    lc = b.o(1100, 400, "map 0 1 200 9000 1 80")
    b.c(f[5], lc, 1)
    ll = b.o(1100, 430, "map 0 7 0.6 1.2 0 300")
    b.c(lt, ll)
    cut = b.mul(lc, ll, 1100, 460)
    for sh, lp in outs:
        b.c(cut, lp, 0, 1)
    b.c(outs[0][1], b.o(220, 870, "meter~ 5"), 1)  # salida lowpass (outlet 1) de vcf~
    vol = b.sq(f[1], 1300, 400)
    fin = []
    for k, (sh, lp) in enumerate(outs):
        x = 20 + k * 300
        mk2 = b.o(x, 900, "*~ 1.6")
        b.c(lp, mk2, 1)
        sc = b.o(x, 930, "softclip")
        b.c(mk2, sc)
        v = b.mul(sc, vol, x, 960)
        fin.append(v)
    b.meter(fin[0], 1, 120, 990)
    b.c(micg, b.o(240, 470, "meter~ 6"))
    b.out(fin[0], fin[1], 20, 1020)
    return b.p


def enjambre():
    b = B()
    f = [None] + [b.fader(n, d) for n, d in ((1, .8), (2, .7), (3, .4), (4, .5), (5, .4), (6, .3))]
    u = b.ori(); lt = b.light(); sk = b.shake()
    mic = b.mic(20, 400)
    mg = b.o(20, 460, "map 0 1 0 6 0 60")
    b.c(f[2], mg, 1)
    mi = b.mul(mic, mg, 20, 490)
    msc = b.o(20, 520, "softclip")
    b.c(mi, msc)
    b.meter(msc, 2, 120, 520)
    # aliento: ruido por un filtro angosto que deriva, para que haya materia aunque no entre nada
    nz = b.o(200, 400, "noise~")
    dr = b.chain(b.o(300, 400, "osc~ 0.031"), b.o(300, 430, "*~ 600"), b.o(300, 460, "+~ 900"))
    br = b.o(200, 490, "vcf~ 25")
    b.c(nz, br, 0, 0); b.c(dr, br, 0, 1)
    brg = b.o(200, 520, "*~ 1.6")
    b.c(br, brg)
    buf = b.o(20, 600, "delwrite~ $0-grain 5000")
    b.c(msc, buf); b.c(brg, buf)
    # densidad: DENS * beta ; pitch: PTCH * gamma ; tamaño: SIZE
    dd = b.o(500, 400, "map 0 1 1 80 1 60")
    b.c(f[3], dd, 1)
    db = b.o(600, 400, "map -90 90 0.4 2.5 1 60")
    b.c(u, db, 1)
    dens = fmul(b, dd, db, 500, 460)
    iv = interval(b, (dens), 500, 500)
    pp = b.o(700, 400, "map 0 1 0.25 2 1 60")
    b.c(f[4], pp, 1)
    pg = b.o(800, 400, "map -90 90 0.5 2 1 60")
    b.c(u, pg, 2)
    ratio = fmul(b, pp, pg, 700, 460)
    # SIZE: escala la duración (30..330 ms) por 0.3..1.8 -> máx ~600 ms, cabe en el buffer de 5 s
    sz = b.o(900, 400, "map 0 1 0.3 1.8 1 60")
    b.c(f[5], sz, 1)
    cl, met = grain_cloud(b, 500, 640, 16, (iv, 0), (ratio, 0), 300, 30, 1800, 2100, 0.5, size=(sz, 1))
    gr = b.o(500, 960, "*~ 3.5")
    b.c(cl, gr)
    b.meter(gr, 3, 600, 960)
    b.meter(gr, 4, 600, 990)
    b.meter(gr, 5, 600, 1020)
    # FBK: los granos vuelven al buffer
    fb = b.o(1000, 400, "map 0 1 0 0.85 0 60")
    b.c(f[6], fb, 1)
    fbs = b.mul(gr, fb, 300, 600)
    fsc = b.o(300, 630, "softclip")
    b.c(fbs, fsc); b.c(fsc, buf)
    b.meter(fsc, 6, 400, 630)
    # sacudón: ráfaga (metro rápido 300 ms)
    burst = b.m(900, 140, "15")
    b.c(sk, burst); b.c(burst, met, 0, 1)
    back = b.o(980, 140, "delay 300")
    b.c(sk, back)
    b.c(back, dens)  # bang: rehace el intervalo desde la densidad actual
    # luz: oscuro = más brillo en el aliento
    lg = b.o(1100, 400, "map 0 7 1.5 0.4 0 300")
    b.c(lt, lg)
    brl = b.mul(brg, lg, 200, 550)
    b.c(brl, buf)
    vol = b.sq(f[1], 1300, 400)
    mix = b.o(20, 1000, "+~")
    b.c(gr, mix, 0, 0); b.c(msc, mix, 0, 1)
    m2 = b.o(20, 1030, "*~ 0.8")
    b.c(mix, m2)
    sc = b.o(20, 1060, "softclip")
    b.c(m2, sc)
    v = b.mul(sc, vol, 20, 1090)
    b.meter(v, 1, 120, 1090)
    r = b.width(v, 200, 1090)
    b.out(v, r, 20, 1150)
    # SIZE real: conecta el factor a la duración de la nube (random 300 -> * size)
    return b.p


def aullido():
    b = B()
    f = [None] + [b.fader(n, d) for n, d in ((1, .8), (2, .6), (3, .4), (4, .5), (5, .5), (6, .3))]
    u = b.ori(); gy = b.gyro(); lt = b.light(); sk = b.shake()
    # TIME base * gamma ; jitter por giro
    tb = b.o(300, 400, "map 0 1 0.7 120 1 200")
    b.c(f[3], tb, 1)
    tg = b.o(400, 400, "map -90 90 0.5 2 1 300")
    b.c(u, tg, 2)
    T = b.mul(tb, tg, 300, 430)
    jd = b.o(500, 400, "map 0 6 0 0.2 0 200")
    b.c(gy, jd)
    jit = b.chain(b.o(600, 400, "osc~ 0.3"))
    jm = b.mul(jit, jd, 600, 430)
    j1 = b.o(600, 460, "+~ 1")
    b.c(jm, j1)
    Tj = b.mul(T, j1, 300, 470)
    # centro de resonancia por gamma, Q por fader * luz
    cc = b.o(700, 400, "map -90 90 150 3000 1 300")
    b.c(u, cc, 2)
    qf = b.o(800, 400, "map 0 1 1 40 1 100")
    b.c(f[4], qf, 1)
    ql = b.o(900, 400, "map 0 7 1.5 0.7 0 300")
    b.c(lt, ql)
    Q = fmul(b, qf, ql, 800, 460)
    dv = b.o(1000, 400, "map 0 1 1 20 1 100")
    b.c(f[5], dv, 1)
    gf = b.o(1100, 400, "map 0 1 0.4 1.5 0 100")
    b.c(f[2], gf, 1)
    gb = b.o(1200, 400, "map -90 90 0.8 1.25 0 300")
    b.c(u, gb, 1)
    G = b.mul(gf, gb, 1100, 440)
    sw = b.chain(b.m(800, 140, "1 5, 0 1500 5"), b.o(800, 170, "vline~"))
    b.c(sk, b.p.n - 2)
    swg = b.o(800, 200, "*~ 0.35")
    b.c(sw, swg)
    Gs = b.o(1100, 470, "+~")
    b.c(G, Gs, 0, 0); b.c(swg, Gs, 0, 1)
    # excitación: mic + piso de ruido + golpe de ruido del sacudón
    mic = b.mic(20, 400)
    mg = b.o(20, 460, "map 0 1 0 8 0 60")
    b.c(f[6], mg, 1)
    mi = b.mul(mic, mg, 20, 490)
    b.meter(mi, 6, 120, 490)
    nz = b.o(150, 400, "noise~")
    nb = b.o(150, 430, "*~ 0.004")
    b.c(nz, nb)
    nk = b.mul(nz, sw, 150, 460)
    exc = b.o(20, 530, "*~ 1")
    for s_ in (mi, nb, nk):
        b.c(s_, exc)
    H = [[1, 1, 1, 1], [1, -1, 1, -1], [1, 1, -1, -1], [1, -1, -1, 1]]
    ratios = (1, 1.27, 1.61, 2.03)
    cent = (1, 1.5, 2.2, 3.1)
    writes, outs = [], []
    for k in range(4):
        x = 20 + k * 300
        w = b.o(x, 1000, f"delwrite~ $0-a{k} 2000")
        b.c(exc, w)
        writes.append(w)
    for k in range(4):
        x = 20 + k * 300
        tk = b.o(x, 600, f"*~ {ratios[k]}")
        b.c(Tj, tk)
        rd = b.o(x, 630, f"delread4~ $0-a{k}")
        b.c(tk, rd)
        ck = b.o(x + 100, 630, f"*~ {cent[k]}")
        b.c(cc, ck)
        bp = b.o(x, 660, "vcf~ 8")
        b.c(rd, bp, 0, 0); b.c(ck, bp, 0, 1); b.c(Q, bp, 0, 2)
        dk = b.mul(bp, dv, x, 690)
        sc = b.o(x, 720, "softclip")
        b.c(dk, sc)
        gk = b.mul(sc, Gs, x, 750)
        hh = b.o(x, 780, "hip~ 30")
        b.c(gk, hh)
        outs.append(hh)
        b.meter(hh, 2 + k, x + 100, 780)
        for j in range(4):
            mm = b.o(x + j * 60, 830, f"*~ {0.5 * H[j][k]}")
            b.c(hh, mm); b.c(mm, writes[j])
    L = b.o(20, 1060, "+~")
    b.c(outs[0], L, 0, 0); b.c(outs[2], L, 0, 1)
    R = b.o(320, 1060, "+~")
    b.c(outs[1], R, 0, 0); b.c(outs[3], R, 0, 1)
    vol = b.sq(f[1], 1300, 450)
    fin = []
    for k, s_ in enumerate((L, R)):
        x = 20 + k * 300
        h = b.o(x, 1090, "*~ 0.5")
        b.c(s_, h)
        sc = b.o(x, 1120, "softclip")
        b.c(h, sc)
        fin.append(b.mul(sc, vol, x, 1150))
    b.meter(fin[0], 1, 120, 1150)
    b.out(fin[0], fin[1], 20, 1190)
    return b.p


def ruina():
    b = B()
    f = [None] + [b.fader(n, d) for n, d in ((1, .8), (2, .6), (3, .5), (4, .3), (5, .3), (6, .4))]
    u = b.ori(); gy = b.gyro(); sk = b.shake()
    # fuente: mic + un FM chico para que siempre haya materia
    mic = b.mic(20, 400)
    mg = b.o(20, 460, "map 0 1 0 6 0 60")
    b.c(f[2], mg, 1)
    mi = b.mul(mic, mg, 20, 490)
    oa = b.o(200, 400, "osc~ 113")
    ob = b.o(300, 400, "osc~ 71")
    obk = b.o(300, 430, "*~ 340")
    b.c(ob, obk)
    b.c(obk, oa)
    fa = b.o(200, 460, "*~ 0.25")
    b.c(oa, fa)
    src = b.o(20, 530, "softclip")
    b.c(mi, src); b.c(fa, src)
    b.meter(src, 2, 120, 530)
    # buffer de 2 s regrabado en ciclo
    tab = b.o(600, 600, "table $0-buf 96000")
    tw = b.o(20, 600, "tabwrite~ $0-buf")
    b.c(src, tw)
    rec = b.o(200, 600, "metro 2000")
    on = b.m(200, 570, "1")
    b.c(b.lb, on); b.c(on, rec); b.c(rec, tw)
    # RATE: cada cuánto salta a un nuevo trozo (beta la acelera)
    rt = b.o(400, 400, "map 0 1 600 40 1 60")
    b.c(f[6], rt, 1)
    rb = b.o(500, 400, "map -90 90 1.6 0.6 1 200")
    b.c(u, rb, 1)
    per = fmul(b, rt, rb, 400, 460)
    tick = b.o(400, 500, "metro 200")
    on2 = b.m(400, 470, "1")
    b.c(b.lb, on2); b.c(on2, tick); b.c(per, tick, 0, 1)
    # largo del trozo por gamma (15..400 ms)
    ln = b.o(600, 400, "map -90 90 15 400 1 100")
    b.c(u, ln, 2)
    tt = b.o(400, 540, "t b b")
    b.c(tick, tt)
    st = b.o(400, 570, "random 86000")
    b.c(tt, st, 1)
    sst = b.o(400, 600, "sig~")
    b.c(st, sst)
    lenf = b.o(700, 440, "t f f")
    b.c(ln, lenf, 1)
    hz = b.o(700, 470, "swap 1000")
    hzd = b.o(700, 500, "/")
    b.c(lenf, hz, 0); b.c(hz, hzd, 1, 1); b.c(hz, hzd, 0, 0)
    ph = b.o(700, 540, "phasor~")
    b.c(hzd, ph)
    ph0 = b.m(800, 540, "0")
    b.c(tt, ph0, 0); b.c(ph0, ph, 0, 1)
    smp = b.o(800, 470, "* 48")
    b.c(lenf, smp, 1)
    span = b.o(700, 570, "*~")
    b.c(ph, span, 0, 0); b.c(smp, span, 0, 1)
    idx = b.o(700, 600, "+~")
    b.c(span, idx, 0, 0); b.c(sst, idx, 0, 1)
    rdt = b.o(700, 630, "tabread4~ $0-buf")
    b.c(idx, rdt)
    # STUT: mezcla entre fuente viva y tartamudeo
    stf = f[3]
    inv = b.o(900, 600, "-~ 1")
    b.c(stf, inv)
    ng = b.o(900, 630, "*~ -1")
    b.c(inv, ng)
    a1 = b.mul(src, ng, 20, 680)
    a2 = b.mul(rdt, stf, 300, 680)
    b.meter(a2, 3, 400, 680)
    mix = b.o(20, 720, "+~")
    b.c(a1, mix, 0, 0); b.c(a2, mix, 0, 1)
    # CUT: en cada salto, corta con probabilidad = fader
    cp = b.o(1000, 400, "map 0 1 0 90 0 60")
    b.c(f[4], cp, 1)
    rr = b.o(1000, 540, "random 100")
    b.c(tt, rr, 0)
    mo = b.o(1000, 570, "moses 0")
    b.c(cp, mo, 1, 1)
    b.c(rr, mo)
    z = b.m(1000, 600, "0 3")
    o1 = b.m(1060, 600, "1 3")
    b.c(mo, z, 0); b.c(mo, o1, 1)
    gl = b.o(1000, 630, "line~")
    b.c(z, gl); b.c(o1, gl)
    cut = b.mul(mix, gl, 20, 760)
    b.meter(gl, 4, 1100, 630)
    # CRSH: sample & hold + cuantización (floor via wrap~), el giro lo empeora
    cr = b.o(1100, 400, "map 0 1 22000 300 1 60")
    b.c(f[5], cr, 1)
    gk = b.o(1200, 400, "map 0 6 1 0.3 0 200")
    b.c(gy, gk)
    rate = b.mul(cr, gk, 1100, 440)
    php = b.o(1100, 470, "phasor~")
    b.c(rate, php)
    sh = b.o(20, 800, "samphold~")
    b.c(cut, sh, 0, 0); b.c(php, sh, 0, 1)
    lv = b.o(1300, 400, "map 0 1 256 3 1 60")
    b.c(f[5], lv, 1)
    k1 = b.mul(sh, lv, 20, 830)
    h5 = b.o(20, 860, "+~ 0.5")
    b.c(k1, h5)
    wr = b.o(120, 890, "wrap~")
    b.c(h5, wr)
    fl = b.o(20, 920, "-~")
    b.c(h5, fl, 0, 0); b.c(wr, fl, 0, 1)
    qd = b.o(20, 950, "/~")
    b.c(fl, qd, 0, 0); b.c(lv, qd, 0, 1)
    b.meter(qd, 5, 120, 950)
    b.meter(rdt, 6, 800, 660)
    sw = b.chain(b.m(900, 140, "0"))
    b.c(sk, sw); b.c(sw, ph, 0, 1)
    vol = b.sq(f[1], 1400, 450)
    sc = b.o(20, 990, "softclip")
    b.c(qd, sc)
    v = b.mul(sc, vol, 20, 1020)
    b.meter(v, 1, 120, 1020)
    r = b.width(v, 200, 1020, ms=17)
    b.out(v, r, 20, 1080)
    return b.p


def abismo():
    b = B()
    f = [None] + [b.fader(n, d) for n, d in ((1, .8), (2, .7), (3, .3), (4, .4), (5, .4), (6, .2))]
    u = b.ori(); gy = b.gyro(); lt = b.light(); sk = b.shake()
    base = b.o(300, 400, "map -90 90 25 110 1 600")
    b.c(u, base, 2)
    dk = b.o(400, 400, "map 0 1 0 0.03 0 200")
    b.c(f[4], dk, 1)
    # FM del giro sobre las sierras
    gfm = b.o(500, 400, "map 0 6 0 0.3 0 200")
    b.c(gy, gfm)
    vib = b.o(600, 400, "osc~ 5.3")
    vb = b.mul(vib, gfm, 600, 430)
    v1 = b.o(600, 460, "+~ 1")
    b.c(vb, v1)
    fb0 = b.mul(base, v1, 300, 460)
    saws = b.o(300, 640, "*~ 0.3")
    for k, (lf, det) in enumerate(((0.05, 1.0), (0.071, 1.005), (0.093, 0.997))):
        x = 20 + k * 200
        l = b.o(x, 500, f"osc~ {lf}")
        lm = b.mul(l, dk, x, 530)
        l1 = b.o(x, 560, f"+~ {det}")
        b.c(lm, l1)
        fq = b.mul(fb0, l1, x, 590)
        ph = b.o(x, 620, "phasor~")
        b.c(fq, ph)
        s2 = b.o(x, 650, "*~ 2")
        b.c(ph, s2)
        s3 = b.o(x, 680, "-~ 1")
        b.c(s2, s3)
        b.c(s3, saws)
    b.meter(saws, 4, 400, 640)
    # SUB: seno una octava abajo
    sb = b.o(700, 500, "*~ 0.5")
    b.c(base, sb)
    so = b.o(700, 530, "osc~")
    b.c(sb, so)
    sub = b.mul(so, f[2], 700, 560)
    b.meter(sub, 2, 800, 560)
    # RING: modulación en anillo con un portador inarmónico (alpha) + mic
    rc = b.o(900, 400, "map -1 1 3.1 7.7 0 600")
    rad = b.o(900, 340, "* 0.0174533")
    b.c(u, rad, 0)
    cs = b.o(900, 370, "cos")
    b.c(rad, cs); b.c(cs, rc)
    cf = b.mul(base, rc, 900, 440)
    car = b.o(900, 470, "osc~")
    b.c(cf, car)
    mic = b.mic(1100, 400)
    mg = b.o(1100, 460, "map 0 1 0 6 0 60")
    b.c(f[6], mg, 1)
    mi = b.mul(mic, mg, 1100, 490)
    b.meter(mi, 6, 1200, 490)
    carm = b.o(900, 520, "+~")
    b.c(car, carm, 0, 0); b.c(mi, carm, 0, 1)
    rg = f[3]
    rm = b.mul(saws, carm, 300, 700)
    wet = b.mul(rm, rg, 300, 730)
    inv = b.o(500, 700, "-~ 1")
    b.c(rg, inv)
    ng = b.o(500, 730, "*~ -1")
    b.c(inv, ng)
    dry = b.mul(saws, ng, 500, 760)
    b.meter(wet, 3, 400, 730)
    body = b.o(300, 800, "+~")
    b.c(wet, body, 0, 0); b.c(dry, body, 0, 1)
    b.c(sub, body, 0, 1)
    # DIST con hinchazón del sacudón
    dd = b.o(1300, 400, "map 0 1 1 30 1 100")
    b.c(f[5], dd, 1)
    sw = b.chain(b.m(800, 140, "1 10, 0 2000 10"), b.o(800, 170, "vline~"))
    b.c(sk, b.p.n - 2)
    swk = b.o(800, 200, "*~ 4")
    b.c(sw, swk)
    s1 = b.o(800, 230, "+~ 1")
    b.c(swk, s1)
    drv = b.mul(dd, s1, 1300, 440)
    dsig = b.mul(body, drv, 300, 840)
    dsc = b.o(300, 870, "softclip")
    b.c(dsig, dsc)
    b.meter(dsc, 5, 400, 870)
    # filtro: beta * luz
    fc = b.o(1400, 400, "map -90 90 80 3000 1 300")
    b.c(u, fc, 1)
    fl = b.o(1500, 400, "map 0 7 0.6 1.3 0 300")
    b.c(lt, fl)
    cut = b.mul(fc, fl, 1400, 440)
    lp = b.o(300, 910, "vcf~ 3")
    b.c(dsc, lp, 0, 0); b.c(cut, lp, 0, 1)
    vol = b.sq(f[1], 1500, 480)
    mk = b.o(300, 940, "*~ 1.8")
    b.c(lp, mk, 1)
    sc = b.o(300, 970, "softclip")
    b.c(mk, sc)
    v = b.mul(sc, vol, 300, 1000)
    b.meter(v, 1, 400, 1000)
    r = b.width(v, 500, 1000, ms=7)
    b.out(v, r, 300, 1060)
    return b.p


def cadaver():
    b = B()
    f = [None] + [b.fader(n, d) for n, d in ((1, .8), (2, .5), (3, .4), (4, .5), (5, .4), (6, .2))]
    u = b.ori(); lt = b.light(); sk = b.shake()
    tab = b.o(600, 600, "table $0-smp 480000")
    # dn-sample <ruta>: la app manda un WAV al azar de samples/ o rec/ al cargar y en cada sacudón
    rs = b.o(20, 400, "r dn-sample")
    lp = b.o(20, 430, "list prepend read -resize -maxsize 2880000")
    la = b.o(20, 460, "list append $0-smp")
    lt_ = b.o(20, 490, "list trim")
    sf = b.o(20, 520, "soundfiler")
    b.chain(rs, lp, la, lt_, sf)
    # largo del sample en muestras -> multiplicador de posición
    st = b.o(500, 840, "* 480000")
    b.c(sf, st, 0, 1)
    # sin samples: graba 10 s del mic en la tabla
    rn = b.o(300, 400, "r dn-sample-none")
    mic = b.mic(300, 440)
    tw = b.o(300, 500, "tabwrite~ $0-smp")
    b.c(mic, tw); b.c(rn, tw)
    n0 = b.m(400, 430, "480000")
    b.c(rn, n0); b.c(n0, st, 0, 1)
    # pedir otro sample en cada sacudón
    nx = b.m(800, 140, "sample next")
    b.c(sk, nx); b.c(nx, b.o(800, 170, "s toapp"))
    # densidad: DENS * beta
    dd = b.o(500, 400, "map 0 1 1 60 1 60")
    b.c(f[3], dd, 1)
    db = b.o(600, 400, "map -90 90 0.4 2.5 1 60")
    b.c(u, db, 1)
    dens = fmul(b, dd, db, 500, 460)
    iv = interval(b, dens, 500, 500)
    pp = b.o(700, 400, "map 0 1 0.25 2 1 60")
    b.c(f[4], pp, 1)
    pg = b.o(800, 400, "map -90 90 0.5 2 1 60")
    b.c(u, pg, 2)
    ratio = fmul(b, pp, pg, 700, 460)
    sz = b.o(900, 400, "map 0 1 30 600 1 60")
    b.c(f[5], sz, 1)
    # dispersión por luz: oscuro = la nube se abre por todo el sample
    spr = b.o(1000, 400, "map 0 7 0.6 0.05 0 300")
    b.c(lt, spr)
    met = b.o(500, 600, "metro 100")
    on = b.m(500, 570, "1")
    b.c(b.lb, on); b.c(on, met); b.c(iv, met, 0, 1)
    tm = b.o(500, 630, "t b b b b b")
    b.c(met, tm)
    cnt = b.o(1000, 660, "f")
    inc = b.o(1040, 660, "+ 1")
    md = b.o(1040, 690, "mod 16")
    b.c(tm, cnt, 4); b.chain(cnt, inc, md); b.c(md, cnt, 0, 1)
    lpp = b.o(500, 900, "list prepend 0")
    b.c(cnt, lpp, 0, 1)
    am = b.m(900, 660, "0.45")
    b.c(tm, am, 3)
    # dur = SIZE * (0.5..1.5)
    rdur = b.o(800, 660, "random 100")
    b.c(tm, rdur, 2)
    rd1 = b.chain(rdur, b.o(800, 690, "/ 100"), b.o(800, 720, "+ 0.5"))
    durm = b.o(800, 750, "*")
    b.c(sz, durm, 1, 1); b.c(rd1, durm)
    tdur = b.o(800, 780, "t f f")
    b.c(durm, tdur)
    # inicio = (POS + ruido*spr) * N
    rpos = b.o(500, 660, "random 1000")
    b.c(tm, rpos, 1)
    rp1 = b.chain(rpos, b.o(500, 690, "/ 1000"), b.o(500, 720, "- 0.5"))
    rp2 = b.o(500, 750, "*")
    b.c(spr, rp2, 1, 1); b.c(rp1, rp2)
    pos = b.o(500, 780, "+")
    b.c(f[2], pos, 1, 1); b.c(rp2, pos)
    pcl = b.o(500, 810, "clip 0 0.98")
    b.c(pos, pcl)
    b.c(pcl, st)
    # fin = inicio + ratio * dur * 48
    span = b.o(700, 840, "*")
    b.c(ratio, span, 0, 1)
    sp48 = b.o(700, 810, "* 48")
    b.c(tdur, sp48, 1); b.c(sp48, span)
    tst = b.o(500, 870, "t f f")
    b.c(st, tst)
    en = b.o(600, 900, "+")
    b.c(span, en, 0, 1); b.c(tst, en, 1, 0)
    pk = b.o(500, 940, "pack f f f f")
    b.c(am, pk, 0, 3); b.c(tdur, pk, 0, 2); b.c(en, pk, 0, 1); b.c(tst, pk, 0, 0)
    b.c(pk, lpp)
    cl = b.o(500, 970, "clone grain_tab 16 $0")
    b.c(lpp, cl)
    gr = b.o(500, 1000, "*~ 1.3")
    b.c(cl, gr)
    for n in (2, 3, 4, 5):
        b.meter(gr, n, 600 + n * 40, 1000)
    mg = b.o(20, 900, "map 0 1 0 4 0 60")
    b.c(f[6], mg, 1)
    mi = b.mul(mic, mg, 20, 930)
    b.meter(mi, 6, 120, 930)
    mix = b.o(20, 1030, "+~")
    b.c(gr, mix, 0, 0); b.c(mi, mix, 0, 1)
    sc = b.o(20, 1060, "softclip")
    b.c(mix, sc)
    vol = b.sq(f[1], 1300, 450)
    v = b.mul(sc, vol, 20, 1090)
    b.meter(v, 1, 120, 1090)
    r = b.width(v, 200, 1090, ms=9)
    b.out(v, r, 20, 1150)
    return b.p


BANK = [
    ("I", matadero, "maquina",
     "núcleo de feedback, FM caótico, drone, mic brutal y nube granular",
     [("VOL", .8), ("MIC", .5), ("FDBK", .6), ("FM", .3), ("DRN", .5), ("GRN", .6)]),
    ("II", muro, "wall",
     "muro de ruido: drive, comb realimentado, crush y lowpass",
     [("VOL", .8), ("GRIT", .5), ("CRSH", .3), ("COMB", .5), ("LOW", .6), ("MIC", .3)]),
    ("III", enjambre, "granular",
     "nube granular del mic con aliento propio y realimentación",
     [("VOL", .8), ("MIC", .7), ("DENS", .4), ("PTCH", .5), ("SIZE", .4), ("FBK", .3)]),
    ("IV", aullido, "feedback",
     "red de 4 delays cruzados que aúlla y resuena",
     [("VOL", .8), ("GAIN", .6), ("TIME", .4), ("Q", .5), ("SAT", .5), ("MIC", .3)]),
    ("V", ruina, "glitch",
     "tartamudeo de un buffer, cortes al azar y crush",
     [("VOL", .8), ("MIC", .6), ("STUT", .5), ("CUT", .3), ("CRSH", .3), ("RATE", .4)]),
    ("VI", abismo, "drone",
     "drone sub-grave: sierras con deriva, ring mod y distorsión",
     [("VOL", .8), ("SUB", .7), ("RING", .3), ("DRFT", .4), ("DIST", .4), ("MIC", .2)]),
    ("VII", cadaver, "samples",
     "granula tus samples y grabaciones; sacudón = otro sample",
     [("VOL", .8), ("POS", .5), ("DENS", .4), ("PTCH", .5), ("SIZE", .4), ("MIC", .2)]),
]


def main():
    lib = OUT / "lib"
    lib.mkdir(parents=True, exist_ok=True)
    for name, fn in (("mag3", g.mag3), ("map", g.map_), ("shake", g.shake), ("softclip", g.softclip),
                     ("grain", grain), ("grain_tab", grain_tab), ("fader", fader), ("held", held),
                     ("meter~", meter), ("out~", out)):
        (lib / f"{name}.pd").write_text(fn().render())
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "master.pd").write_text(master().render())
    for name, fn, cat, desc, faders in BANK:
        d = OUT / "bank" / name
        d.mkdir(parents=True, exist_ok=True)
        (d / "main.pd").write_text(fn().render())
        txt = [f"cat: {cat}", f"desc: {desc}"] + [f"{l} {v}" for l, v in faders]
        (d / "faders.txt").write_text("\n".join(txt) + "\n")
        print("banco:", name)


if __name__ == "__main__":
    main()
