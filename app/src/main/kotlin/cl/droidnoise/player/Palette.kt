package cl.droidnoise.player

/** Paleta rojo sangre (de la muestra "noise brutal death" del usuario): negro, granates, carmín, hueso. */
object Palette {
    const val BG = 0xFF0F0202.toInt()
    const val SURFACE = 0xFF1D0203.toInt()
    const val FG = 0xFFECCED0.toInt()
    const val MUTED = 0xFFC0646A.toInt()
    const val DEEP = 0xFF580C0F.toInt()
    const val ACCENT_BG = 0xFF85181F.toInt()
    const val ACCENT = 0xFFAC1B28.toInt()
    const val HOLD = 0xFFECCED0.toInt()
    /** Elementos apagados (segmentos de medidor, espinas de luz, grilla). */
    const val OFF = 0xFF2A0405.toInt()
    /** Gesto vivo atenuado mientras HOLD está activo. */
    const val DIM = 0xFF580C0F.toInt()
}
