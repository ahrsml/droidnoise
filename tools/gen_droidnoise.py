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
    p = Patch(1400, 900)
    X = lambda col: 20 + col * 170  # noqa: E731

    # ---------- sensores -> parámetros ----------
    r_ori = p.obj(X(0), 20, "r ori")
    u_ori = p.obj(X(0), 50, "unpack f f f")
    # beta: densidad de granos + cutoff
    m_dens = p.obj(X(0), 90, "map -90 90 1 40 1 80")
    sw = p.obj(X(0), 120, "swap 1000")
    dv = p.obj(X(0), 150, "/")
    ti = p.obj(X(0), 180, "t f f")
    sp_i = p.obj(X(0), 210, "spigot 1")
    st_i = p.obj(X(1), 210, "f 200")
    m_cut = p.obj(X(1), 90, "map -90 90 300 9000 1 60")
    # gamma: pitch de granos + base de resonadores
    m_pitch = p.obj(X(2), 90, "map -90 90 0.25 4 1 80")
    m_res = p.obj(X(3), 90, "map -90 90 60 600 1 120")
    # alpha (brújula): pan y tiempo de feedback, por sin/cos para no saltar en ±180
    rad = p.obj(X(4), 90, "* 0.0174533")
    tr = p.obj(X(4), 120, "t f f")
    sn = p.obj(X(4), 150, "sin")
    cs = p.obj(X(5), 150, "cos")
    m_pan = p.obj(X(4), 180, "map -1 1 0 1 0 150")
    m_fbt = p.obj(X(5), 180, "map -1 1 30 900 1 300")
    # luz: más oscuro = más feedback
    r_l = p.obj(X(6), 20, "r light")
    l1 = p.obj(X(6), 50, "+ 1")
    lg = p.obj(X(6), 80, "log")
    m_fba = p.obj(X(6), 110, "map 0 7 0.92 0.05 0 300")
    # giroscopio: girar = crush
    r_g = p.obj(X(7), 20, "r gyro")
    mg = p.obj(X(7), 50, "mag3")
    tg = p.obj(X(7), 80, "t f f")
    m_cmix = p.obj(X(7), 110, "map 0 8 0 1 0 100")
    m_crate = p.obj(X(7), 140, "map 0 8 16000 700 1 100")
    # proximidad: gate
    r_p = p.obj(X(6), 170, "r proxnear")
    eq = p.obj(X(6), 200, "== 0")
    mg_ = p.msg(X(6), 230, "$1 20")
    gate = p.obj(X(6), 260, "line~")
    # sacudón
    r_a = p.obj(X(2), 150, "r acc")
    sk = p.obj(X(2), 180, "shake 8 2 300")
    tsk = p.obj(X(2), 210, "t b b b")
    vib = p.msg(X(3), 240, "vibrate 40")
    s_app = p.obj(X(3), 270, "s toapp")

    p.c(r_ori, u_ori)
    p.c(u_ori, m_dens, 1); p.c(u_ori, m_cut, 1)
    p.c(u_ori, m_pitch, 2); p.c(u_ori, m_res, 2)
    p.c(u_ori, rad, 0); p.c(rad, tr); p.c(tr, cs, 1); p.c(tr, sn, 0)
    p.c(sn, m_pan); p.c(cs, m_fbt)
    p.c(m_dens, sw, 1); p.c(sw, dv, 1, 1); p.c(sw, dv, 0, 0); p.c(dv, ti)
    p.c(ti, st_i, 1, 1); p.c(ti, sp_i, 0, 0)
    p.c(r_l, l1); p.c(l1, lg); p.c(lg, m_fba)
    p.c(r_g, mg); p.c(mg, tg); p.c(tg, m_crate, 1); p.c(tg, m_cmix, 0)
    p.c(r_p, eq); p.c(eq, mg_); p.c(mg_, gate)
    p.c(r_a, sk); p.c(sk, tsk)
    p.c(tsk, vib, 2); p.c(vib, s_app)

    # ---------- generador de granos ----------
    lb = p.obj(X(0), 260, "loadbang")
    t_lb = p.obj(X(0), 290, "t b b")
    on = p.msg(X(0), 320, "1")
    open_gate = p.msg(X(1), 320, "1")
    met = p.obj(X(0), 360, "metro 200")
    tm = p.obj(X(0), 390, "t b b b b b")
    cnt = p.obj(X(4), 420, "f")
    inc = p.obj(X(4) + 50, 420, "+ 1")
    md = p.obj(X(4) + 50, 450, "mod 8")
    a_amp = p.msg(X(3), 420, "0.35")
    rj = p.obj(X(2), 420, "random 100")
    rj1 = p.obj(X(2), 450, "- 50")
    rj2 = p.obj(X(2), 480, "/ 1000")
    rj3 = p.obj(X(2), 510, "+ 1")
    rj4 = p.obj(X(2), 540, "* 1")
    rdur = p.obj(X(1), 420, "random 160")
    rdur1 = p.obj(X(1), 450, "+ 40")
    rpos = p.obj(X(0), 420, "random 3000")
    rpos1 = p.obj(X(0), 450, "+ 700")
    pk = p.obj(X(0), 580, "pack f f f f")
    lp = p.obj(X(0), 610, "list prepend 0")
    cl = p.obj(X(0), 640, "clone grain 8")
    # ráfaga del sacudón: metro rápido 300 ms, después vuelve al intervalo guardado
    b0 = p.msg(X(1), 250, "0")
    b15 = p.msg(X(1) + 40, 250, "15")
    bd = p.obj(X(1) + 90, 250, "delay 300")
    b1 = p.msg(X(1) + 90, 280, "1")

    p.c(lb, t_lb); p.c(t_lb, open_gate, 1); p.c(open_gate, mg_)
    p.c(t_lb, on, 0); p.c(on, met)
    p.c(sp_i, met, 0, 1)
    p.c(met, tm)
    p.c(tm, cnt, 4); p.c(cnt, inc); p.c(inc, md); p.c(md, cnt, 0, 1); p.c(cnt, lp, 0, 1)
    p.c(tm, a_amp, 3); p.c(a_amp, pk, 0, 3)
    p.c(tm, rj, 2); p.c(rj, rj1); p.c(rj1, rj2); p.c(rj2, rj3); p.c(rj3, rj4); p.c(rj4, pk, 0, 2)
    p.c(m_pitch, rj4, 1, 1)
    p.c(tm, rdur, 1); p.c(rdur, rdur1); p.c(rdur1, pk, 0, 1)
    p.c(tm, rpos, 0); p.c(rpos, rpos1); p.c(rpos1, pk, 0, 0)
    p.c(pk, lp); p.c(lp, cl)
    p.c(tsk, b0, 0); p.c(b0, sp_i, 0, 1)
    p.c(tsk, b15, 0); p.c(b15, met, 0, 1)
    p.c(tsk, bd, 0); p.c(bd, b1); p.c(b1, sp_i, 0, 1); p.c(bd, st_i); p.c(st_i, met, 0, 1)

    # ---------- audio ----------
    Y = 700
    adc = p.obj(X(0), Y, "adc~ 1")
    hp = p.obj(X(0), Y + 30, "hip~ 60")
    mic = p.obj(X(0), Y + 60, "*~ 0.8")
    nz = p.obj(X(1), Y, "noise~")
    burst_msg = p.msg(X(1) + 70, Y, "1 2, 0 400 2")
    burst = p.obj(X(1) + 70, Y + 30, "vline~")
    nb = p.obj(X(1), Y + 60, "*~")
    bed = p.obj(X(1) + 70, Y + 60, "*~ 0.03")
    th_mic = p.obj(X(0), Y + 90, "throw~ dn-pre")
    th_nb = p.obj(X(1), Y + 90, "throw~ dn-pre")
    th_bed = p.obj(X(1) + 70, Y + 90, "throw~ dn-pre")
    pre = p.obj(X(2), Y, "catch~ dn-pre")
    dw = p.obj(X(2), Y + 30, "delwrite~ dn-grain 4000")
    dry = p.obj(X(2) + 100, Y + 30, "*~ 0.6")
    gw = p.obj(X(0), 670, "*~ 0.8")
    mix1 = p.obj(X(3), Y + 60, "hip~ 30")
    # resonadores: 4 vcf~ en paralelo, razones inarmónicas sobre la base de gamma
    res_out = p.obj(X(3), Y + 160, "*~ 0.4")
    mix1_d = p.obj(X(3) + 80, Y + 160, "*~ 0.7")
    for k, ratio in enumerate((1, 1.51, 2.27, 3.41)):
        fr = p.obj(X(4) + k * 80, Y + 60, f"*~ {ratio}")
        vf = p.obj(X(4) + k * 80, Y + 100, "vcf~ 15")
        p.c(m_res, fr, 0)
        p.c(mix1, vf, 0, 0); p.c(fr, vf, 0, 1)
        p.c(vf, res_out, 0, 0)
    mix2 = p.obj(X(3), Y + 200, "hip~ 20")
    # crush: samphold~ a la tasa del giro, mezcla por magnitud del giro
    ph = p.obj(X(5), Y + 200, "phasor~")
    sh = p.obj(X(4), Y + 240, "samphold~")
    dif = p.obj(X(4), Y + 270, "-~")
    cm = p.obj(X(4), Y + 300, "*~")
    mix3 = p.obj(X(4), Y + 330, "+~")
    # filtro maestro (vcf~ lowpass, cutoff por señal desde beta).
    # vcf~ con q <= 1 se vuelve inestable sobre ~8 kHz y el NaN envenena el loop de feedback.
    lpf = p.obj(X(4), Y + 360, "vcf~ 2")
    # feedback
    fbw = p.obj(X(6), Y + 360, "delwrite~ dn-fb 2000")
    fbr = p.obj(X(6), Y + 200, "delread4~ dn-fb")
    fbs = p.obj(X(6), Y + 230, "softclip")
    fba = p.obj(X(6), Y + 260, "*~")
    fbh = p.obj(X(6), Y + 290, "hip~ 40")
    fbt = p.obj(X(6), Y + 320, "throw~ dn-pre")
    # salida
    gt = p.obj(X(4), Y + 400, "*~")
    vol = p.obj(X(4), Y + 430, "*~ 0.7")
    lim = p.obj(X(4), Y + 460, "softclip")
    lc = p.obj(X(4), Y + 490, "clip~ -0.95 0.95")
    pl = p.obj(X(5), Y + 430, "*~ 0.25")
    pr = p.obj(X(5) + 80, Y + 430, "*~ -0.25")
    pr1 = p.obj(X(5) + 80, Y + 460, "+~ 0.25")
    cl_ = p.obj(X(5), Y + 460, "cos~")
    cr = p.obj(X(5) + 80, Y + 490, "cos~")
    gl = p.obj(X(4), Y + 530, "*~")
    gr = p.obj(X(5), Y + 530, "*~")
    dac = p.obj(X(4), Y + 570, "dac~ 1 2")
    ev = p.obj(X(6), Y + 530, "env~ 8192")
    sm = p.obj(X(6), Y + 560, "s dn-meter")

    p.c(adc, hp); p.c(hp, mic); p.c(mic, th_mic)
    p.c(tsk, burst_msg, 1); p.c(burst_msg, burst)
    p.c(nz, nb, 0, 0); p.c(burst, nb, 0, 1); p.c(nb, th_nb)
    p.c(nz, bed); p.c(bed, th_bed)
    p.c(pre, dw); p.c(pre, dry)
    p.c(cl, gw)
    p.c(dry, mix1); p.c(gw, mix1)
    p.c(mix1, mix1_d)
    p.c(res_out, mix2); p.c(mix1_d, mix2)
    p.c(m_crate, ph, 0); p.c(ph, sh, 0, 1)
    p.c(mix2, sh, 0, 0)
    p.c(sh, dif, 0, 0); p.c(mix2, dif, 0, 1)
    p.c(dif, cm, 0, 0); p.c(m_cmix, cm, 0, 1)
    p.c(cm, mix3, 0, 0); p.c(mix2, mix3, 0, 1)
    p.c(mix3, lpf, 0, 0); p.c(m_cut, lpf, 0, 1)
    p.c(lpf, fbw, 1)
    p.c(m_fbt, fbr, 0); p.c(fbr, fbs); p.c(fbs, fba, 0, 0); p.c(m_fba, fba, 0, 1)
    p.c(fba, fbh); p.c(fbh, fbt)
    p.c(lpf, gt, 1, 0); p.c(gate, gt, 0, 1)
    p.c(gt, vol); p.c(vol, lim); p.c(lim, lc)
    p.c(m_pan, pl, 0); p.c(m_pan, pr, 0); p.c(pr, pr1)
    p.c(pl, cl_); p.c(pr1, cr)
    p.c(lc, gl, 0, 0); p.c(cl_, gl, 0, 1)
    p.c(lc, gr, 0, 0); p.c(cr, gr, 0, 1)
    p.c(gl, dac, 0, 0); p.c(gr, dac, 0, 1)
    p.c(lc, ev); p.c(ev, sm)

    p.text(X(8), 20, "DroidNoise fase 2 - generado por tools/gen_droidnoise.py")
    p.text(X(8), 50, "beta: densidad de granos + cutoff maestro")
    p.text(X(8), 75, "gamma: pitch de granos + base de resonadores")
    p.text(X(8), 100, "alpha (brujula): pan + tiempo de feedback")
    p.text(X(8), 125, "luz: oscuro = mas feedback")
    p.text(X(8), 150, "giro: crush (samphold~)")
    p.text(X(8), 175, "sacudon: rafaga de granos + noise + vibracion")
    p.text(X(8), 200, "proximidad: gate")
    return p


def main_entry():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in (("mag3", mag3), ("map", map_), ("shake", shake), ("grain", grain),
                     ("softclip", softclip), ("main", main)):
        (OUT / f"{name}.pd").write_text(fn().render())
        print(f"escrito {OUT / name}.pd")


if __name__ == "__main__":
    main_entry()
