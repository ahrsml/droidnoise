#!/usr/bin/env python3
"""Genera pd/droidnoise/*.pd (Fase 2) con las conexiones indexadas automáticamente.

Solo objetos vanilla presentes en Pd 0.51 (el de libpd 1.2.1-rc6): nada de expr,
ni lop~ con cutoff por señal. Si editas los .pd a mano en Pd, no vuelvas a correr esto
sin portar los cambios.

Uso: python3 tools/gen_droidnoise.py
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "pd" / "droidnoise"


class Patch:
    def __init__(self, w=900, h=700):
        self.lines = [f"#N canvas 0 50 {w} {h} 12;"]
        self.n = 0
        self.conns = []
        self.texts = []

    def _add(self, kind, x, y, text):
        text = text.replace("$", "\\$").replace(",", " \\,").replace(";", " \\;")
        self.lines.append(f"#X {kind} {x} {y} {text};")
        self.n += 1
        return self.n - 1

    def obj(self, x, y, text):
        return self._add("obj", x, y, text)

    def msg(self, x, y, text):
        return self._add("msg", x, y, text)

    def text(self, x, y, text):
        # Los comentarios van al final para no correr los índices.
        self.texts.append((x, y, text))

    def c(self, a, b, outlet=0, inlet=0):
        self.conns.append(f"#X connect {a} {outlet} {b} {inlet};")

    def render(self):
        out = list(self.lines)
        for x, y, t in self.texts:
            t = t.replace(",", " \\,").replace(";", " \\;")
            out.append(f"#X text {x} {y} {t};")
        return "\n".join(out + self.conns) + "\n"


def mag3():
    """Lista x y z -> magnitud."""
    p = Patch(400, 300)
    i = p.obj(20, 20, "inlet")
    u = p.obj(20, 50, "unpack f f f")
    tz = p.obj(200, 80, "t f f"); mz = p.obj(200, 110, "*")
    ty = p.obj(110, 80, "t f f"); my = p.obj(110, 110, "*")
    tx = p.obj(20, 80, "t f f"); mx = p.obj(20, 110, "*")
    a = p.obj(110, 150, "+")
    b = p.obj(20, 190, "+")
    s = p.obj(20, 220, "sqrt")
    o = p.obj(20, 250, "outlet")
    p.c(i, u)
    # unpack dispara z, y, x (derecha a izquierda).
    for out, t, m in ((2, tz, mz), (1, ty, my), (0, tx, mx)):
        p.c(u, t, out)
        p.c(t, m, 1, 1)
        p.c(t, m, 0, 0)
    p.c(mz, a, 0, 1)
    p.c(my, a, 0, 0)
    p.c(a, b, 0, 1)
    p.c(mx, b, 0, 0)
    p.c(b, s)
    p.c(s, o)
    p.text(150, 220, "magnitud de x y z")
    return p


def map_():
    """[map inmin inmax outmin outmax exp? slew_ms] -> outlet~ suavizado, outlet float."""
    p = Patch(700, 500)
    i = p.obj(20, 20, "inlet")
    clip = p.obj(20, 50, "clip $1 $2")
    sub = p.obj(20, 80, "- $1")
    div = p.obj(20, 110, "/ 1")
    t = p.obj(20, 140, "t f f")
    sp_lin = p.obj(20, 170, "spigot")
    sp_exp = p.obj(200, 170, "spigot")
    mul_lin = p.obj(20, 200, "* 1")
    add_lin = p.obj(20, 230, "+ $3")
    mul_exp = p.obj(200, 200, "* 1")
    ex = p.obj(200, 230, "exp")
    mul_ex2 = p.obj(200, 260, "* $3")
    out_t = p.obj(20, 300, "t f f")
    pk = p.obj(20, 330, "pack f $6")
    ln = p.obj(20, 360, "line~")
    o_sig = p.obj(20, 400, "outlet~")
    o_f = p.obj(250, 400, "outlet")

    lb = p.obj(400, 20, "loadbang")
    tb = p.obj(400, 50, "t b b b b b")
    # rango de entrada
    f2 = p.obj(560, 90, "f $2"); s1 = p.obj(560, 120, "- $1")
    # rango lineal de salida
    f4a = p.obj(470, 90, "f $4"); s3 = p.obj(470, 120, "- $3")
    # factor exponencial ln(outmax/outmin)
    f4b = p.obj(400, 160, "f $4"); d3 = p.obj(400, 190, "/ $3"); lg = p.obj(400, 220, "log")
    # selector de curva
    f5 = p.obj(520, 160, "f $5"); t5 = p.obj(520, 190, "t f f")
    eq0 = p.obj(520, 220, "== 0"); ne0 = p.obj(600, 220, "!= 0")
    # valor inicial: el centro del rango de entrada
    f1 = p.obj(300, 90, "f $1"); a2 = p.obj(300, 120, "+ $2"); h = p.obj(300, 150, "* 0.5")

    p.c(i, clip); p.c(clip, sub); p.c(sub, div); p.c(div, t)
    p.c(t, sp_exp, 1, 0); p.c(t, sp_lin, 0, 0)
    p.c(sp_lin, mul_lin); p.c(mul_lin, add_lin); p.c(add_lin, out_t)
    p.c(sp_exp, mul_exp); p.c(mul_exp, ex); p.c(ex, mul_ex2); p.c(mul_ex2, out_t)
    p.c(out_t, o_f, 1, 0); p.c(out_t, pk, 0, 0); p.c(pk, ln); p.c(ln, o_sig)

    p.c(lb, tb)
    p.c(tb, f2, 4); p.c(f2, s1); p.c(s1, div, 0, 1)
    p.c(tb, f4a, 3); p.c(f4a, s3); p.c(s3, mul_lin, 0, 1)
    p.c(tb, f4b, 2); p.c(f4b, d3); p.c(d3, lg); p.c(lg, mul_exp, 0, 1)
    p.c(tb, f5, 1); p.c(f5, t5); p.c(t5, ne0, 1); p.c(t5, eq0, 0)
    p.c(eq0, sp_lin, 0, 1); p.c(ne0, sp_exp, 0, 1)
    p.c(tb, f1, 0); p.c(f1, a2); p.c(a2, h); p.c(h, clip)
    p.text(20, 440, "map inmin inmax outmin outmax curva(0 lin / 1 exp) slew_ms")
    p.text(20, 465, "izq: señal suavizada (line~). der: float sin suavizar")
    return p


def shake():
    """[shake umbral_alto umbral_bajo refractario_ms]: acc -> jerk, histéresis, refractario."""
    p = Patch(600, 450)
    i = p.obj(20, 20, "inlet")
    m = p.obj(20, 50, "mag3")
    t = p.obj(20, 80, "t f f")
    sub = p.obj(20, 110, "-")
    ab = p.obj(20, 140, "abs")
    hi = p.obj(20, 170, "moses $1")
    lo = p.obj(20, 200, "moses $2")
    canre = p.obj(20, 230, "spigot")
    tre = p.obj(20, 260, "t b b")
    m1 = p.msg(20, 290, "1")
    m0a = p.msg(80, 290, "0")
    armed = p.obj(250, 200, "spigot")
    tf = p.obj(250, 230, "t f b")
    m0b = p.msg(330, 260, "0")
    dl = p.obj(400, 260, "delay $3")
    m1b = p.msg(400, 290, "1")
    o = p.obj(250, 330, "outlet")
    lb = p.obj(450, 20, "loadbang")
    m1c = p.msg(450, 50, "1")

    p.c(i, m); p.c(m, t)
    p.c(t, sub, 1, 0); p.c(t, sub, 0, 1)
    p.c(sub, ab); p.c(ab, hi)
    p.c(hi, lo, 0); p.c(hi, armed, 1, 0)
    p.c(lo, canre, 0)
    p.c(canre, tre)
    p.c(tre, m0a, 1); p.c(m0a, canre, 0, 1)
    p.c(tre, m1, 0); p.c(m1, armed, 0, 1)
    p.c(armed, tf)
    p.c(tf, m0b, 1); p.c(m0b, armed, 0, 1)
    p.c(tf, dl, 1); p.c(dl, m1b); p.c(m1b, canre, 0, 1)
    p.c(tf, o, 0)
    p.c(lb, m1c); p.c(m1c, armed, 0, 1)
    p.text(20, 380, "dispara con jerk >= alto si está armado; se rearma bajo el umbral bajo")
    p.text(20, 405, "y solo después del refractario. Sale la intensidad del jerk.")
    return p


def grain():
    """Voz de grano: lista d0_ms dur_ms ratio amp. Lee de dn-grain con rampa de delay (pitch)."""
    p = Patch(600, 450)
    i = p.obj(20, 20, "inlet")
    t = p.obj(20, 50, "t l l")
    u = p.obj(250, 80, "unpack f f f f")
    amp = p.obj(500, 300, "*~")
    neg = p.obj(380, 110, "* -1")
    one = p.obj(380, 135, "+ 1")
    mul = p.obj(300, 170, "*")
    td = p.obj(300, 110, "t f f")
    t0 = p.obj(250, 200, "t f f")
    add = p.obj(320, 230, "+")
    pk = p.obj(250, 260, "pack f f f")
    mr = p.msg(250, 290, "$1, $2 $3")
    vd = p.obj(250, 320, "vline~")
    rd = p.obj(250, 350, "delread4~ dn-grain")
    me = p.msg(20, 110, "0, 1 $2")
    ve = p.obj(20, 140, "vline~")
    cs = p.obj(20, 170, "cos~")
    sc = p.obj(20, 200, "*~ -0.5")
    of = p.obj(20, 230, "+~ 0.5")
    env = p.obj(20, 300, "*~")
    o = p.obj(20, 400, "outlet~")

    p.c(i, t)
    p.c(t, u, 1)
    # unpack: amp, ratio, dur, d0
    p.c(u, amp, 3, 1)
    p.c(u, neg, 2); p.c(neg, one); p.c(one, mul, 0, 1)
    p.c(u, td, 1); p.c(td, pk, 1, 2); p.c(td, mul, 0, 0)
    p.c(mul, add, 0, 1)
    p.c(u, t0, 0); p.c(t0, add, 1, 0); p.c(add, pk, 0, 1); p.c(t0, pk, 0, 0)
    p.c(pk, mr); p.c(mr, vd); p.c(vd, rd)
    p.c(t, me, 0); p.c(me, ve); p.c(ve, cs); p.c(cs, sc); p.c(sc, of)
    p.c(of, env, 0, 0); p.c(rd, env, 0, 1)
    p.c(env, amp, 0, 0); p.c(amp, o)
    p.text(20, 430, "d0 dur ratio amp: delay de d0 a d0+(1-ratio)*dur en dur ms con ventana Hann")
    return p


def softclip():
    """y = 1.5x - 0.5x^3 con x en [-1 1]: saturación suave sin expr~."""
    p = Patch(400, 300)
    i = p.obj(20, 20, "inlet~")
    cl = p.obj(20, 50, "clip~ -1 1")
    sq = p.obj(120, 80, "*~")
    cu = p.obj(120, 110, "*~")
    k3 = p.obj(120, 140, "*~ -0.5")
    k1 = p.obj(20, 140, "*~ 1.5")
    o = p.obj(20, 200, "outlet~")
    p.c(i, cl)
    p.c(cl, sq, 0, 0); p.c(cl, sq, 0, 1)
    p.c(sq, cu, 0, 0); p.c(cl, cu, 0, 1)
    p.c(cu, k3); p.c(cl, k1)
    p.c(k1, o); p.c(k3, o)
    return p


def main():
    """v2: máquina de ruido. Genera sonido propio (núcleo de feedback, FM caótico, drone)
    + mic brutal + nube granular. Faders de la app por dn-*, HOLD congela los gestos."""
    p = Patch(1500, 1100)
    X = lambda col: 20 + col * 170  # noqa: E731

    # ---------- faders (desde la app) y HOLD ----------
    # Cada fader: [r dn-x] -> cuadrado (curva de volumen) -> line~ 60 ms. Default en loadbang.
    lb = p.obj(X(8), 20, "loadbang")
    faders = {}
    faders_lin = {}
    for k, (name, default) in enumerate((("vol", 0.8), ("mic", 0.5), ("core", 0.6),
                                         ("chaos", 0.3), ("drone", 0.5), ("grains", 0.6))):
        y = 60 + k * 30
        r = p.obj(X(8), y, f"r dn-{name}")
        d = p.msg(X(8) - 60, y, str(default))
        t = p.obj(X(9), y, "t f f")
        sq = p.obj(X(9) + 50, y, "*")
        pk = p.obj(X(10), y, "pack f 60")
        ln = p.obj(X(10) + 80, y, "line~")
        p.c(lb, d); p.c(d, r)  # un msg conectado a [r] no hace nada: se manda abajo
        p.conns.pop()
        p.c(d, t); p.c(r, t)
        p.c(t, sq, 1, 1); p.c(t, sq, 0, 0); p.c(sq, pk); p.c(pk, ln)
        faders[name] = ln
        # versión lineal (sin curva) para ganancias de loop
        pk2 = p.obj(X(10) + 140, y, "pack f 60")
        ln2 = p.obj(X(10) + 210, y, "line~")
        p.c(r, pk2); p.c(d, pk2); p.c(pk2, ln2)
        faders_lin[name] = ln2
    r_hold = p.obj(X(8), 260, "r hold")
    nh = p.obj(X(8), 290, "== 0")

    def held(recv, x, y):
        r = p.obj(x, y, f"r {recv}")
        sp = p.obj(x, y + 25, "spigot 1")
        p.c(r, sp); p.c(nh, sp, 0, 1)
        return sp

    # ---------- sensores -> parámetros ----------
    ori = held("ori", X(0), 20)
    u_ori = p.obj(X(0), 75, "unpack f f f")
    # beta: intensidad (drive del mic, ganancia de feedback, cutoff)
    m_drive = p.obj(X(0), 110, "map -90 90 3 80 1 200")
    m_fb = p.obj(X(0), 140, "map -90 90 0.5 1.35 0 300")
    m_cut = p.obj(X(0), 170, "map -90 90 400 9000 1 150")
    # gamma: espacio de pitch (tiempos del núcleo, resonancias, FM, granos)
    m_t = p.obj(X(1), 110, "map -90 90 40 1.5 1 400")
    m_c = p.obj(X(1), 140, "map -90 90 120 4000 1 300")
    m_fma = p.obj(X(1), 170, "map -90 90 30 900 1 300")
    m_gp = p.obj(X(1), 200, "map -90 90 0.5 2 1 80")
    # alpha: pan y razón de FM, por sin/cos para no saltar en ±180
    rad = p.obj(X(2), 110, "* 0.0174533")
    tr = p.obj(X(2), 140, "t f f")
    sn = p.obj(X(2), 170, "sin")
    cs = p.obj(X(3), 170, "cos")
    m_pan = p.obj(X(2), 200, "map -1 1 0.1 0.9 0 300")
    m_ratio = p.obj(X(3), 200, "map -1 1 0.51 3.7 0 600")
    # giro: crush + índice de FM
    gyro = held("gyro", X(4), 20)
    mg = p.obj(X(4), 75, "mag3")
    tg = p.obj(X(4), 105, "t f f f")
    m_cmix = p.obj(X(4), 140, "map 0 6 0 1 0 250")
    m_crate = p.obj(X(4), 170, "map 0 6 12000 900 1 250")
    m_idx = p.obj(X(5), 140, "map 0 6 50 3000 1 300")
    # luz: oscuro = resonancias más finas, nube más densa, más drone
    light = held("light", X(6), 20)
    l1 = p.obj(X(6), 75, "+ 1")
    lg = p.obj(X(6), 105, "log")
    tl = p.obj(X(6), 135, "t f f f")
    m_q = p.obj(X(6), 170, "map 0 7 30 4 1 400")
    m_gd = p.obj(X(7), 170, "map 0 7 30 2 1 300")
    m_dl = p.obj(X(6), 200, "map 0 7 1 0.25 0 500")
    # proximidad: gate
    r_p = p.obj(X(6), 240, "r proxnear")
    eq = p.obj(X(6), 270, "== 0")
    mg_ = p.msg(X(6), 300, "$1 20")
    gate = p.obj(X(6), 330, "line~")
    # sacudón: acento (hinchazón de feedback y caos) + vibración
    r_a = p.obj(X(2), 240, "r acc")
    sk = p.obj(X(2), 270, "shake 8 2 300")
    tsk = p.obj(X(2), 300, "t b b")
    vib = p.msg(X(3), 330, "vibrate 40")
    s_app = p.obj(X(3), 360, "s toapp")
    swm = p.msg(X(2), 330, "1 30, 0 1500 30")
    sw = p.obj(X(2), 360, "vline~")
    sw_fb = p.obj(X(2), 390, "*~ 0.4")
    sw_idx = p.obj(X(2) + 80, 390, "*~ 2500")

    p.c(ori, u_ori)
    for m in (m_drive, m_fb, m_cut):
        p.c(u_ori, m, 1)
    for m in (m_t, m_c, m_fma, m_gp):
        p.c(u_ori, m, 2)
    p.c(u_ori, rad, 0); p.c(rad, tr); p.c(tr, cs, 1); p.c(tr, sn, 0)
    p.c(sn, m_pan); p.c(cs, m_ratio)
    p.c(gyro, mg); p.c(mg, tg)
    p.c(tg, m_idx, 2); p.c(tg, m_crate, 1); p.c(tg, m_cmix, 0)
    p.c(light, l1); p.c(l1, lg); p.c(lg, tl)
    p.c(tl, m_dl, 2); p.c(tl, m_gd, 1); p.c(tl, m_q, 0)
    p.c(r_p, eq); p.c(eq, mg_); p.c(mg_, gate)
    p.c(r_a, sk); p.c(sk, tsk)
    p.c(tsk, vib, 1); p.c(vib, s_app)
    p.c(tsk, swm, 0); p.c(swm, sw); p.c(sw, sw_fb); p.c(sw, sw_idx)
    p.c(lb, r_hold)  # placeholder para mantener el orden; se quita abajo
    p.conns.pop()
    p.c(r_hold, nh)
    op = p.msg(X(6) + 80, 300, "1")
    p.c(lb, op); p.c(op, mg_)

    Y = 450
    # ---------- mic brutal ----------
    adc = p.obj(X(0), Y, "adc~ 1")
    hp = p.obj(X(0), Y + 30, "hip~ 80")
    drv = p.obj(X(0), Y + 60, "*~")
    msc = p.obj(X(0), Y + 90, "softclip")
    mic = p.obj(X(0), Y + 120, "*~")
    p.c(adc, hp); p.c(hp, drv); p.c(m_drive, drv, 0, 1); p.c(drv, msc)
    p.c(msc, mic); p.c(faders["mic"], mic, 0, 1)

    # ---------- FM caótico: dos osc~ que se modulan mutuamente (1 bloque de retardo) ----------
    oa = p.obj(X(1), Y + 60, "osc~")
    ob = p.obj(X(2), Y + 60, "osc~")
    rb = p.obj(X(1), Y, "delread~ dn-chb 0")
    ra = p.obj(X(2), Y, "delread~ dn-cha 0")
    ia = p.obj(X(1), Y + 30, "*~")
    ib = p.obj(X(2), Y + 30, "*~")
    fb_ = p.obj(X(2) + 80, Y + 30, "*~")
    wa = p.obj(X(1) + 80, Y + 90, "delwrite~ dn-cha 50")
    wb = p.obj(X(2) + 80, Y + 90, "delwrite~ dn-chb 50")
    ring = p.obj(X(1), Y + 120, "*~")
    rs = p.obj(X(1), Y + 150, "*~ 2")
    csc = p.obj(X(1), Y + 180, "softclip")
    chaos = p.obj(X(1), Y + 210, "*~")
    p.c(rb, ia, 0, 0); p.c(m_idx, ia, 0, 1); p.c(sw_idx, ia, 0, 1)
    p.c(ra, ib, 0, 0); p.c(m_idx, ib, 0, 1); p.c(sw_idx, ib, 0, 1)
    p.c(m_fma, oa); p.c(ia, oa)
    p.c(m_fma, fb_, 0, 0); p.c(m_ratio, fb_, 0, 1); p.c(fb_, ob); p.c(ib, ob)
    p.c(oa, wa); p.c(ob, wb)
    p.c(oa, ring, 0, 0); p.c(ob, ring, 0, 1); p.c(ring, rs); p.c(oa, rs)
    p.c(rs, csc); p.c(csc, chaos); p.c(faders["chaos"], chaos, 0, 1)

    # ---------- drone: ruido por 3 resonadores angostos con deriva lenta ----------
    nz = p.obj(X(3), Y, "noise~")
    drift = p.obj(X(4), Y, "osc~ 0.03")
    dd = p.obj(X(4), Y + 30, "*~ 0.04")
    d1 = p.obj(X(4), Y + 60, "+~ 1")
    dc = p.obj(X(4), Y + 90, "*~")
    dsum = p.obj(X(3), Y + 160, "*~ 6")
    p.c(drift, dd); p.c(dd, d1); p.c(d1, dc, 0, 0); p.c(m_c, dc, 0, 1)
    for k, ratio in enumerate((0.5, 0.75, 1.13)):
        fr = p.obj(X(3) + k * 60, Y + 100, f"*~ {ratio}")
        vf = p.obj(X(3) + k * 60, Y + 130, "vcf~ 40")
        p.c(dc, fr); p.c(nz, vf, 0, 0); p.c(fr, vf, 0, 1); p.c(vf, dsum)
    dsc = p.obj(X(3), Y + 190, "softclip")
    dlv = p.obj(X(3), Y + 220, "*~")
    drone = p.obj(X(3), Y + 250, "*~")
    p.c(dsum, dsc); p.c(dsc, dlv); p.c(m_dl, dlv, 0, 1)
    p.c(dlv, drone); p.c(faders["drone"], drone, 0, 1)

    # ---------- núcleo: red de feedback 2x2 (delay + bp + saturación + ganancia > 1) ----------
    Z = Y + 300
    srcs = p.obj(X(0), Z, "*~ 0.5")  # entrada al núcleo: mic + caos + ruido bajo
    bed = p.obj(X(0) + 80, Z, "*~ 0.02")
    p.c(mic, srcs); p.c(chaos, srcs); p.c(nz, bed)
    lfo1 = p.obj(X(1), Z, "osc~ 0.07")
    lfo2 = p.obj(X(1) + 80, Z, "osc~ 0.043")
    t1a = p.obj(X(1), Z + 30, "*~ 0.05")
    t2a = p.obj(X(1) + 80, Z + 30, "*~ 0.05")
    t1b = p.obj(X(1), Z + 60, "+~ 1")
    t2b = p.obj(X(1) + 80, Z + 60, "+~ 1.37")
    t1 = p.obj(X(1), Z + 90, "*~")
    t2 = p.obj(X(1) + 80, Z + 90, "*~")
    p.c(lfo1, t1a); p.c(t1a, t1b); p.c(t1b, t1, 0, 0); p.c(m_t, t1, 0, 1)
    p.c(lfo2, t2a); p.c(t2a, t2b); p.c(t2b, t2, 0, 0); p.c(m_t, t2, 0, 1)
    c2 = p.obj(X(2), Z + 90, "*~ 1.6")
    p.c(m_c, c2)
    # ganancia del loop = gesto * fader lineal * 1.5: con el fader en 0.6 y el celu plano
    # queda en ~0.83 (resuena); inclinado pasa de 1 y auto-oscila (acotado por softclip).
    gain = p.obj(X(4), Z + 30, "*~")
    gk = p.obj(X(4) + 60, Z, "*~ 1.5")
    p.c(faders_lin["core"], gk)
    p.c(m_fb, gain, 0, 0); p.c(gk, gain, 0, 1)
    gsum = p.obj(X(4), Z + 60, "+~")
    p.c(gain, gsum, 0, 0); p.c(sw_fb, gsum, 0, 1)
    dw1 = p.obj(X(0), Z + 300, "delwrite~ dn-d1 1000")
    dw2 = p.obj(X(2), Z + 300, "delwrite~ dn-d2 1000")
    paths = []
    for k, (tt, cc, dw_x) in enumerate(((t1, m_c, X(0)), (t2, c2, X(2)))):
        x = X(2 + 2 * k)
        rd = p.obj(x, Z + 130, f"delread4~ dn-d{k + 1}")
        bp = p.obj(x, Z + 160, "vcf~ 8")
        dr = p.obj(x, Z + 190, "*~ 3")
        sc = p.obj(x, Z + 220, "softclip")
        g = p.obj(x, Z + 250, "*~")
        hh = p.obj(x, Z + 275, "hip~ 30")
        p.c(tt, rd); p.c(rd, bp, 0, 0); p.c(cc, bp, 0, 1); p.c(m_q, bp, 1, 2)  # q de vcf~ es float
        p.c(bp, dr); p.c(dr, sc); p.c(sc, g, 0, 0); p.c(gsum, g, 0, 1); p.c(g, hh)
        paths.append(hh)
    # matriz: d1 <- src + .7 p1 + .7 p2 ; d2 <- src + .7 p1 - .7 p2
    h1a = p.obj(X(0), Z + 250, "*~ 0.7")
    h1b = p.obj(X(0) + 70, Z + 250, "*~ 0.7")
    h2a = p.obj(X(2) + 90, Z + 250, "*~ 0.7")
    h2b = p.obj(X(2) + 160, Z + 250, "*~ -0.7")
    p1, p2 = paths
    p.c(p1, h1a); p.c(p2, h1b); p.c(p1, h2a); p.c(p2, h2b)
    for h in (h1a, h1b): p.c(h, dw1)
    for h in (h2a, h2b): p.c(h, dw2)
    p.c(srcs, dw1); p.c(srcs, dw2); p.c(bed, dw1)
    core = p.obj(X(6), Z + 300, "*~ 1")
    p.c(p1, core); p.c(p2, core)
    cf = p.obj(X(6), Z + 330, "*~")
    p.c(core, cf); p.c(faders["core"], cf, 0, 1)

    # ---------- nube granular sobre todo ----------
    G = Z + 380
    gbus = p.obj(X(0), G, "delwrite~ dn-grain 4000")
    for s_ in (mic, chaos, drone, cf):
        p.c(s_, gbus)
    met = p.obj(X(7), G - 80, "metro 100")
    on = p.msg(X(7), G - 110, "1")
    p.c(lb, on); p.c(on, met)
    sw_ = p.obj(X(7) + 60, G - 110, "swap 1000")
    dv = p.obj(X(7) + 60, G - 80, "/")
    p.c(m_gd, sw_, 1); p.c(sw_, dv, 1, 1); p.c(sw_, dv, 0, 0); p.c(dv, met, 0, 1)
    tm = p.obj(X(7), G - 50, "t b b b b b")
    cnt = p.obj(X(9), G - 20, "f")
    inc = p.obj(X(9) + 40, G - 20, "+ 1")
    md = p.obj(X(9) + 40, G + 10, "mod 12")
    a_amp = p.msg(X(9) - 50, G - 20, "0.6")
    rj = p.obj(X(8) + 60, G - 20, "random 100")
    rj1 = p.obj(X(8) + 60, G + 10, "- 50")
    rj2 = p.obj(X(8) + 60, G + 40, "/ 500")
    rj3 = p.obj(X(8) + 60, G + 70, "+ 1")
    rj4 = p.obj(X(8) + 60, G + 100, "* 1")
    rdur = p.obj(X(8), G - 20, "random 550")
    rdur1 = p.obj(X(8), G + 10, "+ 150")
    rpos = p.obj(X(7), G - 20, "random 2200")
    rpos1 = p.obj(X(7), G + 10, "+ 1400")
    pk = p.obj(X(7), G + 130, "pack f f f f")
    lp = p.obj(X(7), G + 160, "list prepend 0")
    cl = p.obj(X(7), G + 190, "clone grain 12")
    grains = p.obj(X(7), G + 220, "*~")
    p.c(met, tm)
    p.c(tm, cnt, 4); p.c(cnt, inc); p.c(inc, md); p.c(md, cnt, 0, 1); p.c(cnt, lp, 0, 1)
    p.c(tm, a_amp, 3); p.c(a_amp, pk, 0, 3)
    p.c(tm, rj, 2); p.c(rj, rj1); p.c(rj1, rj2); p.c(rj2, rj3); p.c(rj3, rj4); p.c(rj4, pk, 0, 2)
    p.c(m_gp, rj4, 1, 1)
    p.c(tm, rdur, 1); p.c(rdur, rdur1); p.c(rdur1, pk, 0, 1)
    p.c(tm, rpos, 0); p.c(rpos, rpos1); p.c(rpos1, pk, 0, 0)
    p.c(pk, lp); p.c(lp, cl); p.c(cl, grains); p.c(faders["grains"], grains, 0, 1)

    # ---------- mezcla, crush, filtro, salida ----------
    M = G + 280
    mix = p.obj(X(0), M, "hip~ 25")
    for s_ in (mic, chaos, drone, cf, grains):
        p.c(s_, mix)
    ph = p.obj(X(1), M, "phasor~")
    sh = p.obj(X(0), M + 30, "samphold~")
    dif = p.obj(X(0), M + 60, "-~")
    cm = p.obj(X(0), M + 90, "*~")
    mx3 = p.obj(X(0), M + 120, "+~")
    lpf = p.obj(X(0), M + 150, "vcf~ 2")
    gt = p.obj(X(0), M + 180, "*~")
    vol = p.obj(X(0), M + 210, "*~")
    mk = p.obj(X(0) + 60, M + 225, "*~ 2.5")  # makeup; el softclip limita
    lim = p.obj(X(0), M + 240, "softclip")
    lc = p.obj(X(0), M + 270, "clip~ -0.95 0.95")
    p.c(m_crate, ph); p.c(ph, sh, 0, 1); p.c(mix, sh, 0, 0)
    p.c(sh, dif, 0, 0); p.c(mix, dif, 0, 1)
    p.c(dif, cm, 0, 0); p.c(m_cmix, cm, 0, 1)
    p.c(cm, mx3, 0, 0); p.c(mix, mx3, 0, 1)
    p.c(mx3, lpf, 0, 0); p.c(m_cut, lpf, 0, 1)
    p.c(lpf, gt, 1, 0); p.c(gate, gt, 0, 1)
    p.c(gt, vol); p.c(faders["vol"], vol, 0, 1)
    p.c(vol, mk); p.c(mk, lim); p.c(lim, lc)
    pl = p.obj(X(1), M + 270, "*~ 0.25")
    pr = p.obj(X(1) + 80, M + 270, "*~ -0.25")
    pr1 = p.obj(X(1) + 80, M + 300, "+~ 0.25")
    cl_ = p.obj(X(1), M + 300, "cos~")
    cr = p.obj(X(1) + 80, M + 330, "cos~")
    gl = p.obj(X(0), M + 340, "*~")
    gr = p.obj(X(1), M + 360, "*~")
    dac = p.obj(X(0), M + 400, "dac~ 1 2")
    p.c(m_pan, pl); p.c(m_pan, pr); p.c(pr, pr1); p.c(pl, cl_); p.c(pr1, cr)
    p.c(lc, gl, 0, 0); p.c(cl_, gl, 0, 1); p.c(lc, gr, 0, 0); p.c(cr, gr, 0, 1)
    p.c(gl, dac, 0, 0); p.c(gr, dac, 0, 1)

    # medidores por rama (dB, 100 = 0 dBFS RMS): r dn-m-<rama>
    for name, s_ in (("out", lc), ("mic", mic), ("chaos", chaos), ("drone", drone),
                     ("core", cf), ("grains", grains)):
        e = p.obj(X(11), M + 20 * len(name), "env~ 16384")
        snd = p.obj(X(11) + 90, M + 20 * len(name), f"s dn-m-{name}")
        p.c(s_, e); p.c(e, snd)

    p.text(X(11), 20, "DroidNoise v2 - generado por tools/gen_droidnoise.py")
    p.text(X(11), 45, "faders app: dn-vol dn-mic dn-core dn-chaos dn-drone dn-grains (0..1)")
    p.text(X(11), 70, "hold 1 congela ori/gyro/light")
    p.text(X(11), 95, "beta: drive mic + ganancia feedback + cutoff")
    p.text(X(11), 120, "gamma: tiempos del nucleo + resonancias + FM + pitch granos")
    p.text(X(11), 145, "alpha: pan + razon FM")
    p.text(X(11), 170, "giro: crush + indice FM")
    p.text(X(11), 195, "luz: oscuro = Q alto + nube densa + drone fuerte")
    p.text(X(11), 220, "sacudon: hinchazon de feedback y caos + vibracion")
    return p


def main_entry():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in (("mag3", mag3), ("map", map_), ("shake", shake), ("grain", grain),
                     ("softclip", softclip), ("main", main)):
        (OUT / f"{name}.pd").write_text(fn().render())
        print(f"escrito {OUT / name}.pd")


if __name__ == "__main__":
    main_entry()
